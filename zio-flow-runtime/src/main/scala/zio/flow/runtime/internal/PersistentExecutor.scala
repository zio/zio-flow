/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.runtime.internal

import zio._
import zio.flow.Remote.UnboundRemoteFunction
import zio.flow.runtime.IndexedStore.Index
import zio.flow.remote.DynamicValueHelpers
import zio.flow.serialization._
import zio.flow._
import PersistentExecutor.GarbageCollectionCommand
import zio.flow.runtime.metrics.{TransactionOutcome, finishedFlowAge, finishedFlowCount, flowTotalExecutionTime}
import zio.flow.runtime.{
  DurableLog,
  DurablePromise,
  ExecutorError,
  KeyValueStore,
  FlowStatus,
  Timestamp,
  ZFlowExecutor,
  metrics
}
import zio.schema.{CaseSet, DeriveSchema, DynamicValue, Schema, TypeId}

import java.nio.charset.StandardCharsets
import java.time.{Duration, OffsetDateTime}
import zio.stm.{TMap, ZSTM}
import zio.stream.ZStream

final case class PersistentExecutor(
  execEnv: ExecutionEnvironment,
  durableLog: DurableLog,
  kvStore: KeyValueStore,
  remoteVariableKvStore: RemoteVariableKeyValueStore,
  operationExecutor: OperationExecutor,
  workflows: TMap[FlowId, Promise[Nothing, PersistentExecutor.RuntimeState]],
  gcQueue: Queue[GarbageCollectionCommand]
) extends ZFlowExecutor {

  import PersistentExecutor._

  private val promiseEnv = ZEnvironment(durableLog, execEnv)

  private def coerceRemote[A](remote: Remote[_]): Remote[A] = remote.asInstanceOf[Remote[A]]

  // synchronous -> will return when work is done.
  def run[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): IO[E, A] =
    for {
      resultPromise <- start(id, flow).orDieWith(_.toException)
      promiseResult <- resultPromise.awaitEither.provideEnvironment(promiseEnv).orDieWith(_.toException)
      _             <- ZIO.log(s"$id finished with $promiseResult")
      result <- promiseResult match {
                  case Left(Left(failure)) => ZIO.die(failure.toException)
                  case Left(Right(dynamicError)) =>
                    ZIO
                      .fromEither(dynamicError.toTypedValue(Schema[E]))
                      .foldZIO(
                        error => ZIO.die(ExecutorError.TypeError("error result", error).toException),
                        success => ZIO.fail(success)
                      )
                  case Right(dynamicSuccess) =>
                    ZIO
                      .fromEither(dynamicSuccess.result.toTypedValue(Schema[A]))
                      .flatMapError(error => ZIO.die(ExecutorError.TypeError("success result", error).toException))
                }
    } yield result

  def start[E, A](
    id: FlowId,
    flow: ZFlow[Any, E, A]
  ): ZIO[Any, ExecutorError, DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]] =
    start(ScopedFlowId.toplevel(id), Timestamp(0L), Index(0L), flow)

  def restartAll(): ZIO[Any, ExecutorError, Unit] =
    for {
      deserializedStates <- kvStore
                              .scanAll(Namespaces.workflowState)
                              .mapError(ExecutorError.KeyValueStoreError("scanAll", _))
                              .mapZIO { case (rawKey, rawState) =>
                                val id = FlowId.unsafeMake(new String(rawKey.toArray, StandardCharsets.UTF_8))
                                ZIO
                                  .fromEither(
                                    execEnv.deserializer.deserialize[PersistentExecutor.State[Any, Any]](rawState)
                                  )
                                  .mapBoth(
                                    error => ExecutorError.DeserializationError(s"state of $id", error),
                                    state =>
                                      (
                                        FlowId.unsafeMake(new String(rawKey.toArray, StandardCharsets.UTF_8)),
                                        state.copy( // TODO: describe as an initial StateChange instead
                                          totalExecutionTime = state.totalExecutionTime + state.currentExecutionTime,
                                          currentExecutionTime = Duration.ZERO
                                        )
                                      )
                                  )
                              }
                              .runCollect
      _ <- ZIO.foreachDiscard(deserializedStates) { case (id, state) =>
             for {
               promise <- Promise.make[Nothing, PersistentExecutor.RuntimeState]
               _       <- ZIO.log(s"Restarting $id")
               _       <- workflows.put(id, promise).commit
               _       <- run(state, promise) @@ metrics.flowStarted(metrics.StartType.Continued)
             } yield ()
           }
      _ <- updateWorkflowMetrics()
    } yield ()

  /** Force a GC run manually */
  override def forceGarbageCollection(): ZIO[Any, Nothing, Unit] =
    Promise
      .make[Nothing, Any]
      .flatMap { finished =>
        gcQueue.offer(GarbageCollectionCommand(finished)) *> finished.await
      }
      .unit

  def poll(id: FlowId): ZIO[Any, ExecutorError, Option[Either[Either[ExecutorError, DynamicValue], DynamicValue]]] =
    workflows.get(id).commit.flatMap {
      case Some(runtimeStatePromise) =>
        runtimeStatePromise.await.flatMap { runtimeState =>
          getResultFromPromise(runtimeState.result)
        }
      case None =>
        loadState(id).flatMap {
          case None =>
            ZIO.fail(ExecutorError.InvalidOperationArguments("Unknown flow id:" + FlowId.unwrap(id)))
          case Some(state) =>
            getResultFromPromise(state.result)
        }
    }

  def delete(id: FlowId): IO[ExecutorError, Unit] =
    for {
      rtsPromise <- workflows.get(id).commit
      rts <- rtsPromise match {
               case Some(promise) => promise.await.map(Some(_))
               case None          => ZIO.none
             }
      _ <- ZIO
             .fail(ExecutorError.InvalidOperationArguments(s"Cannot delete running flow $id"))
             .when(rts.isDefined)
      _     <- ZIO.logInfo(s"Deleting persisted state $id")
      state <- loadState(id)
      _ <- state match {
             case Some(state) =>
               for {
                 _ <- kvStore
                        .delete(Namespaces.workflowState, id.toRaw, None)
                        .mapError(ExecutorError.KeyValueStoreError("delete", _))
                 _ <- state.result.delete().provideEnvironment(promiseEnv)
               } yield ()
             case None => ZIO.unit
           }
    } yield ()

  override def pause(id: FlowId): ZIO[Any, ExecutorError, Unit] =
    workflows.get(id).commit.flatMap {
      case Some(runtimeStatePromise) =>
        for {
          runtimeState <- runtimeStatePromise.await
          _            <- runtimeState.messages.offer(ExecutionCommand.Pause)
        } yield ()
      case None =>
        ZIO.unit
    }

  override def resume(id: FlowId): ZIO[Any, ExecutorError, Unit] =
    workflows.get(id).commit.flatMap {
      case Some(runtimeStatePromise) =>
        for {
          runtimeState <- runtimeStatePromise.await
          _            <- runtimeState.messages.offer(ExecutionCommand.Resume)
        } yield ()
      case None =>
        ZIO.unit
    }
  override def abort(id: FlowId): ZIO[Any, ExecutorError, Unit] =
    interruptFlow(id).unit

  override def getAll: ZStream[Any, ExecutorError, (FlowId, FlowStatus)] =
    kvStore
      .scanAll(Namespaces.workflowState)
      .mapError(ExecutorError.KeyValueStoreError("scanAll", _))
      .mapZIO { case (rawKey, rawState) =>
        val id = FlowId.unsafeMake(new String(rawKey.toArray, StandardCharsets.UTF_8))
        ZIO
          .fromEither(
            execEnv.deserializer.deserialize[PersistentExecutor.State[Any, Any]](rawState)
          )
          .mapBoth(
            error => ExecutorError.DeserializationError(s"state of $id", error),
            state => (id, state.status)
          )
      }

  private def getResultFromPromise(
    resultPromise: DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]
  ): ZIO[Any, ExecutorError, Option[Either[Either[ExecutorError, DynamicValue], DynamicValue]]] =
    resultPromise.poll.provideEnvironment(promiseEnv).map(_.map(_.map(_.result)))

  private def start[E, A](
    id: ScopedFlowId,
    lastTimestamp: Timestamp,
    watchPosition: Index,
    flow: ZFlow[Any, E, A]
  ): ZIO[Any, ExecutorError, DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]] =
    for {
      newPromise <- Promise.make[Nothing, PersistentExecutor.RuntimeState]
      promise <- workflows
                   .get(id.asFlowId)
                   .flatMap(ZSTM.fromOption(_))
                   .catchAll(_ => workflows.put(id.asFlowId, newPromise).as(newPromise))
                   .commit
      result <- if (promise != newPromise) {
                  ZIO.logInfo(s"Flow $id is already running") *> promise.await.map(_.result)
                } else {

                  for {
                    now <- Clock.currentDateTime
                    durablePromise =
                      DurablePromise.make[Either[ExecutorError, DynamicValue], FlowResult](
                        PromiseId(id.asString + "_result")
                      )
                    freshState = State(
                                   id = id,
                                   lastTimestamp = lastTimestamp,
                                   current = flow,
                                   stack = Nil,
                                   result = durablePromise,
                                   envStack = Nil,
                                   transactionStack = Nil,
                                   tempVarCounter = 0,
                                   promiseIdCounter = 0,
                                   forkCounter = 0,
                                   transactionCounter = 0,
                                   status = FlowStatus.Running,
                                   watchedVariables = Set.empty,
                                   watchPosition = watchPosition,
                                   startedAt = now,
                                   suspendedAt = None,
                                   totalExecutionTime = Duration.ZERO,
                                   currentExecutionTime = Duration.ZERO
                                 )
                    state <- loadState(id.asFlowId)
                               .map(
                                 _.getOrElse(freshState).asInstanceOf[State[E, A]]
                               )
                               .map(state =>
                                 state.copy( // TODO: describe as an initial StateChange instead
                                   totalExecutionTime = state.totalExecutionTime + state.currentExecutionTime,
                                   currentExecutionTime = Duration.ZERO
                                 )
                               )
                    result <- run(state, promise) @@ metrics.flowStarted(
                                if (state.startedAt == now) metrics.StartType.Fresh else metrics.StartType.Continued
                              )
                  } yield result
                }
      _ <- updateWorkflowMetrics()
    } yield result

  private def run[E, A](
    initialState: State[E, A],
    promise: Promise[_, PersistentExecutor.RuntimeState]
  ): ZIO[Any, Nothing, DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]] = {
    import zio.flow.ZFlow._

    def step(
      state: State[E, A]
    ): ZIO[
      RemoteContext with VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog,
      ExecutorError,
      StepResult
    ] = {

      def onSuccess(
        value: Remote[_],
        stateChange: StateChange = StateChange.none
      ): ZIO[
        VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog,
        ExecutorError,
        StepResult
      ] = {
        val updatedState = stateChange(state)

        val scope         = updatedState.scope
        val remoteContext = ZLayer(PersistentRemoteContext.make(scope))
//        ZIO.logDebug(s"onSuccess in scope ${scope} with value ${value}") *>
        remoteContext {
          updatedState.stack match {
            case Nil =>
              for {
                result <- RemoteContext.evalDynamic(value)
                _      <- state.result.succeed(FlowResult(result, updatedState.lastTimestamp))
                _ <- updateFinishedFlowMetrics(
                       metrics.FlowResult.Success,
                       updatedState.startedAt,
                       updatedState.totalExecutionTime
                     )
              } yield StepResult(
                stateChange ++ StateChange.done,
                continue = false
              )
            case Instruction.PopEnv :: _ =>
              onSuccess(value, stateChange ++ StateChange.popContinuation ++ StateChange.popEnvironment)
            case Instruction.PushEnv(env) :: _ =>
              onSuccess(value, stateChange ++ StateChange.popContinuation ++ StateChange.pushEnvironment(env))
            case Instruction.Continuation(_, onSuccess) :: _ =>
              RemoteContext.eval(onSuccess.apply(coerceRemote(value))).map { next =>
                StepResult(
                  stateChange ++ StateChange.popContinuation ++ StateChange.setCurrent(next),
                  continue = true
                )
              }
            case Instruction.CaptureRetry(_) :: _ =>
              onSuccess(
                value,
                stateChange ++ StateChange.popContinuation
              )
            case Instruction.CommitTransaction :: _ =>
              for {
                currentContext <- ZIO.service[RemoteContext]
                targetContext <- PersistentRemoteContext.make(
                                   StateChange.leaveTransaction(updatedState).scope
                                 )
                commitSucceeded <-
                  commitModifiedVariablesToParent(updatedState.transactionStack.head, currentContext, targetContext)
                result <-
                  if (commitSucceeded) {
                    for {
                      evaluatedValue <- RemoteContext.evalDynamic(value)
                      result <- onSuccess(
                                  Remote.Literal(evaluatedValue),
                                  stateChange ++ StateChange.popContinuation ++ StateChange.leaveTransaction
                                )
                      _ <- metrics.transactionOutcomeCount(TransactionOutcome.Success).increment
                    } yield result
                  } else {
                    for {
                      _   <- ZIO.logInfo("Commit failed, reverting and retrying")
                      now <- Clock.currentDateTime
                      result = StepResult(
                                 stateChange ++
                                   StateChange.popContinuation ++
                                   StateChange.pushContinuation(Instruction.CommitTransaction) ++
                                   StateChange.restartCurrentTransaction(suspend = false, now),
                                 continue = true
                               )
                      _ <- metrics.transactionOutcomeCount(TransactionOutcome.Retry).increment
                    } yield result
                  }
              } yield result
          }
        }
      }

      def onError(
        value: Remote[_],
        stateChange: StateChange = StateChange.none
      ): ZIO[
        KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with VirtualClock with DurableLog,
        ExecutorError,
        StepResult
      ] = {
        val updatedState = stateChange(state)

        val scope         = updatedState.scope
        val remoteContext = ZLayer(PersistentRemoteContext.make(scope))

        remoteContext {
          updatedState.stack match {
            case Nil =>
              for {
                dyn <- RemoteContext.evalDynamic(value)
                _   <- state.result.fail(Right(dyn))
                _ <- updateFinishedFlowMetrics(
                       metrics.FlowResult.Failure,
                       updatedState.startedAt,
                       updatedState.totalExecutionTime
                     )
              } yield StepResult(
                stateChange ++ StateChange.done,
                continue = false
              )
            case Instruction.PopEnv :: _ =>
              onError(value, stateChange ++ StateChange.popContinuation ++ StateChange.popEnvironment)
            case Instruction.PushEnv(env) :: _ =>
              onError(value, stateChange ++ StateChange.popContinuation ++ StateChange.pushEnvironment(env))
            case Instruction.Continuation(onErrorFun, _) :: _ =>
              val next =
                if (state.isInTransaction) {
                  RemoteContext.evalDynamic(value).map { evaluatedError =>
                    TransactionFailure
                      .unwrapDynamic(evaluatedError)
                      .map(unwrapped => onErrorFun.apply(Remote.Literal(unwrapped)))
                  }
                } else {
                  ZIO.some(onErrorFun.apply(coerceRemote(value)))
                }
              next.flatMap {
                case Some(next) =>
                  RemoteContext.eval(next).map { next =>
                    StepResult(
                      stateChange ++ StateChange.popContinuation ++ StateChange.setCurrent(next),
                      continue = true
                    )
                  }
                case None =>
                  onError(value, stateChange ++ StateChange.popContinuation)
              }
            case Instruction.CaptureRetry(onRetry) :: _ =>
              val next =
                if (state.isInTransaction) {
                  RemoteContext.evalDynamic(value).flatMap { evaluatedError =>
                    TransactionFailure
                      .unwrapDynamic(evaluatedError) match {
                      case None    => ZIO.some(onRetry)
                      case Some(_) => ZIO.none
                    }
                  }
                } else {
                  ZIO.none
                }
              next.flatMap {
                case Some(next) =>
                  ZIO.succeed(
                    StepResult(
                      stateChange ++ StateChange.popContinuation ++ StateChange.setCurrent(next),
                      continue = true
                    )
                  )
                case None =>
                  onError(value, stateChange ++ StateChange.popContinuation)
              }
            case Instruction.CommitTransaction :: _ =>
              RemoteContext.evalDynamic(value).flatMap { schemaAndValue =>
                // Inside a transaction this is always a TransactionFailure which we have to unwrap here
                TransactionFailure.unwrapDynamic(schemaAndValue) match {
                  case Some(failure) =>
                    metrics
                      .transactionOutcomeCount(TransactionOutcome.Failure)
                      .increment
                      .as(
                        StepResult(
                          stateChange ++ StateChange.popContinuation ++ StateChange.revertCurrentTransaction(
                            Remote.Literal(failure)
                          ) ++ StateChange.leaveTransaction,
                          continue = true
                        )
                      )
                  case None =>
                    Clock.currentDateTime.map { now =>
                      StepResult(
                        stateChange ++
                          StateChange.popContinuation ++
                          StateChange.pushContinuation(Instruction.CommitTransaction) ++
                          StateChange.restartCurrentTransaction(suspend = true, now),
                        continue = true
                      )
                    }
                }
              }
          }
        }
      }

      def failWith(error: DynamicValue, stateChange: StateChange = StateChange.none): ZIO[
        KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with VirtualClock with DurableLog,
        ExecutorError,
        StepResult
      ] =
        onError(
          if (state.isInTransaction)
            Remote.Literal(TransactionFailure.wrapDynamic(error))
          else
            Remote.Literal(error),
          stateChange
        )

      metrics.operationCount(state.current.getClass.getSimpleName).increment.zipRight {
        state.current match {
          case Return(value) =>
            onSuccess(value)

          case Now =>
            Clock.instant.flatMap { currInstant =>
              onSuccess(coerceRemote(Remote(currInstant)))
            }

          case Input() =>
            onSuccess(state.currentEnvironment)

          case WaitTill(instant) =>
            for {
              start   <- Clock.instant
              end     <- RemoteContext.eval(instant)(instantSchema)
              duration = Duration.between(start, end)
              _       <- ZIO.logInfo(s"Sleeping for $duration")
              _       <- Clock.sleep(duration)
              _       <- ZIO.logInfo(s"Resuming execution after sleeping $duration")
              result  <- onSuccess(())
            } yield result

          case Read(svar) =>
            for {
              variableReference <- RemoteContext.eval(svar)
              variable           = Remote.Variable(variableReference.name)
              stepResult        <- onSuccess(variable, StateChange.none)
            } yield stepResult

          case Modify(svar, f0) =>
            val f = f0.asInstanceOf[UnboundRemoteFunction[Any, (A, Any)]]
            for {
              variableReference <- RemoteContext.eval(svar)
              variable           = Remote.Variable(variableReference.name)
              dynTuple          <- RemoteContext.evalDynamic(f(variable))
              tuple <- dynTuple match {
                         case DynamicValue.Tuple(dynResult, newValue) => ZIO.succeed((dynResult, newValue))
                         case _                                       => ZIO.fail(ExecutorError.UnexpectedDynamicValue(s"Modify's result was not a tuple"))
                       }
              (dynResult, newValue) = tuple
              _ <-
                RemoteContext.getVariable(
                  variable.identifier
                ) // NOTE: this is needed for variable access tracking to work properly, as f0 may not access the variable at all
              _     <- RemoteContext.setVariable(variable.identifier, newValue)
              result = Remote.Literal(dynResult)
              stepResult <- onSuccess(
                              result,
                              StateChange.none
                            )
            } yield stepResult

          case fold @ Fold(_, _, _) =>
            val cont =
              Instruction.Continuation[fold.ValueR, fold.ValueA, fold.ValueE, fold.ValueE2, fold.ValueB](
                fold.onError,
                fold.onSuccess
              )
            ZIO.succeed(
              StepResult(
                StateChange.setCurrent(fold.value) ++
                  StateChange.pushContinuation(cont),
                continue = true
              )
            )

          case RunActivity(input, activity) =>
            for {
              inp    <- RemoteContext.eval(input)(activity.inputSchema)
              output <- operationExecutor.execute(inp, activity.operation).either
              result <- output match {
                          case Left(error) => failWith(DynamicValueHelpers.of(error))
                          case Right(success) =>
                            val remoteSuccess = Remote(success)(activity.resultSchema.asInstanceOf[Schema[Any]])
                            // TODO: take advantage of activity.check
                            onSuccess(
                              remoteSuccess,
                              StateChange.addCompensation(activity.compensate.provide(remoteSuccess))
                            )
                        }
            } yield result

          case Transaction(flow) =>
            val env = state.currentEnvironment
            ZIO.succeed(
              StepResult(
                StateChange.enterTransaction(flow.provide(coerceRemote(env))) ++
                  StateChange.pushContinuation(Instruction.CommitTransaction) ++
                  StateChange.setCurrent(flow),
                continue = true
              )
            )

          case ensuring @ Ensuring(flow, finalizer) =>
            val cont =
              Instruction.Continuation[Any, ensuring.ValueA, ensuring.ValueE, ensuring.ValueE, ensuring.ValueA](
                UnboundRemoteFunction.make { (e: Remote[ensuring.ValueE]) =>
                  (finalizer *> ZFlow.fail(e).asInstanceOf[ZFlow[Any, ensuring.ValueE, ensuring.ValueA]])
                },
                UnboundRemoteFunction.make { (a: Remote[ensuring.ValueA]) =>
                  (finalizer *> ZFlow.succeed(a).asInstanceOf[ZFlow[Any, ensuring.ValueE, ensuring.ValueA]])
                }
              )

            ZIO.succeed(
              StepResult(StateChange.setCurrent(flow) ++ StateChange.pushContinuation(cont), continue = true)
            )

          case Unwrap(remote) =>
            for {
              evaluatedFlow <- RemoteContext.eval(remote)
            } yield StepResult(StateChange.setCurrent(evaluatedFlow), continue = true)

          case UnwrapRemote(remote) =>
            for {
              evaluated <- RemoteContext.eval(coerceRemote(remote))(Remote.schemaAny)
              result    <- onSuccess(evaluated)
            } yield result

          case fork @ Fork(workflow) =>
            val forkId = state.id.child(FlowId.unsafeMake(s"fork${state.forkCounter}"))
            for {
              resultPromise <- start[fork.ValueE, fork.ValueA](
                                 forkId,
                                 state.lastTimestamp.next,
                                 state.watchPosition,
                                 workflow.asInstanceOf[ZFlow[Any, fork.ValueE, fork.ValueA]]
                               )
              result <- onSuccess(
                          Remote[ExecutingFlow[Any, Any]](ExecutingFlow(forkId.asFlowId, resultPromise.promiseId)),
                          StateChange.increaseForkCounter
                        )
            } yield result

          case await @ Await(execFlow) =>
            for {
              executingFlow <- RemoteContext.eval(execFlow)
              _             <- ZIO.log("Waiting for result")
              result <-
                DurablePromise
                  .make[Either[ExecutorError, DynamicValue], FlowResult](
                    executingFlow
                      .asInstanceOf[ExecutingFlow[Either[ExecutorError, await.ValueE], await.ValueA]]
                      .result
                  )
                  .awaitEither
                  .provideEnvironment(promiseEnv)
              _ <- ZIO.log(s"Await got result: $result")
              stepResult <-
                result.fold(
                  error =>
                    error
                      .fold(
                        failure => ZIO.fail(ExecutorError.AwaitedFlowDied(executingFlow.id, failure)),
                        dynamicError => ZIO.succeed(Remote.RemoteEither(Left(Remote.Literal(dynamicError))))
                      )
                      .flatMap { finishWith =>
                        onSuccess(finishWith)
                      },
                  dynamicSuccess =>
                    onSuccess(
                      Remote.RemoteEither(Right(Remote.Literal(dynamicSuccess.result))),
                      StateChange.advanceClock(dynamicSuccess.timestamp)
                    )
                )
            } yield stepResult

          case timeout @ Timeout(flow, duration) =>
            val forkId = state.id.child(FlowId.unsafeMake(s"timeout${state.forkCounter}"))
            for {
              d <- RemoteContext.eval(duration)
              resultPromise <-
                start[timeout.ValueE, timeout.ValueA](
                  forkId,
                  state.lastTimestamp.next,
                  state.watchPosition,
                  flow.asInstanceOf[ZFlow[Any, timeout.ValueE, timeout.ValueA]]
                )
              result <- resultPromise.awaitEither
                          .provideEnvironment(promiseEnv)
                          .tapErrorCause(c => ZIO.log(s"Failed: $c"))
                          .timeout(d)
              stepResult <- result match {
                              case Some(Right(dynamicSuccess)) =>
                                // succeeded
                                onSuccess(
                                  Remote.Literal(DynamicValue.SomeValue(dynamicSuccess.result)),
                                  StateChange.increaseForkCounter ++ StateChange.advanceClock(dynamicSuccess.timestamp)
                                )
                              case Some(Left(Left(fatal))) =>
                                // failed with fatal error
                                ZIO.fail(ExecutorError.AwaitedFlowDied(forkId.asFlowId, fatal))
                              case Some(Left(Right(dynamicError))) =>
                                // failed with typed error
                                failWith(
                                  dynamicError,
                                  StateChange.increaseForkCounter
                                )
                              case None =>
                                // timed out
                                interruptFlow(forkId.asFlowId).zipRight(
                                  onSuccess(
                                    Remote.Literal(DynamicValue.NoneValue),
                                    StateChange.increaseForkCounter
                                  )
                                )
                            }
            } yield stepResult

          case Provide(value, flow) =>
            ZIO.succeed(
              StepResult(
                StateChange.setCurrent(flow) ++
                  StateChange.pushContinuation(Instruction.PopEnv) ++
                  StateChange.pushEnvironment(value),
                continue = true
              )
            )

          case Die => ZIO.fail(ExecutorError.FlowDied)

          case RetryUntil =>
            onError(Remote.apply[TransactionFailure[ZNothing]](TransactionFailure.Retry))

          case OrTry(left, right) =>
            ZIO.succeed(
              StepResult(
                StateChange.setCurrent(left) ++
                  StateChange.pushContinuation(Instruction.CaptureRetry(right)),
                continue = true
              )
            )

          case Interrupt(remoteExecFlow) =>
            for {
              executingFlow          <- RemoteContext.eval(remoteExecFlow)
              persistentExecutingFlow = executingFlow
              interrupted            <- interruptFlow(persistentExecutingFlow.id)
              result <- if (interrupted)
                          onSuccess(Remote.unit)
                        else
                          failWith(
                            DynamicValueHelpers.of(
                              ActivityError(
                                s"Flow ${persistentExecutingFlow.id} to be interrupted is not running",
                                None
                              )
                            )
                          )
            } yield result

          case Fail(error) =>
            // Evaluating error to make sure it contains no coped variables as it will bubble up the scope stack
            if (state.isInTransaction)
              RemoteContext.evalDynamic(error).flatMap { evaluatedError =>
                failWith(evaluatedError)
              }
            else
              onError(error)

          case NewVar(name, initial) =>
            for {
              initialValue <- RemoteContext.evalDynamic(initial)
              remoteVariableName <-
                RemoteVariableName
                  .make(name)
                  .toZIO
                  .mapError(msg =>
                    ExecutorError.InvalidOperationArguments(s"Failed to create remote variable with name $name: $msg")
                  )
              vref    = RemoteVariableReference[Any](remoteVariableName)
              _      <- RemoteContext.setVariable(remoteVariableName, initialValue)
              _      <- ZIO.logDebug(s"Created new variable $name")
              result <- onSuccess(Remote(vref), StateChange.none)
            } yield result

          case i @ Iterate(initial, step0, predicate) =>
            val tempVarCounter = state.tempVarCounter
            val tempVarName    = s"_zflow_tempvar_${tempVarCounter}"

            def iterate(
              step: Remote.UnboundRemoteFunction[i.ValueA, ZFlow[Any, i.ValueE, i.ValueA]],
              predicate: UnboundRemoteFunction[i.ValueA, Boolean],
              stateVar: Remote[RemoteVariableReference[i.ValueA]],
              boolRemote: Remote[Boolean]
            ): ZFlow[Any, i.ValueE, i.ValueA] =
              ZFlow.recurseSimple[Any, i.ValueE, Boolean](boolRemote) { case (continue, rec) =>
                ZFlow.ifThenElse(continue)(
                  ifTrue = for {
                    a0       <- stateVar.get
                    nextFlow <- step(a0)
                    a1       <- ZFlow.unwrap(nextFlow)
                    _        <- stateVar.set(a1)
                    continue <- predicate(a1)
                    result   <- rec(continue)
                  } yield result,
                  ifFalse = ZFlow.succeed(false)
                )
              } *> stateVar.get

            val zFlow = for {
              stateVar   <- ZFlow.newVar[i.ValueA](tempVarName, initial)
              stateValue <- stateVar.get
              boolRemote <- ZFlow(predicate(stateValue))
              stateValue <- iterate(step0, predicate, stateVar, boolRemote)
            } yield stateValue

            ZIO.succeed(
              StepResult(StateChange.setCurrent(zFlow) ++ StateChange.increaseTempVarCounter, continue = true)
            )

          case Log(remoteMessage) =>
            RemoteContext.eval(remoteMessage).flatMap { message =>
              ZIO.log(message) *> onSuccess(())
            }
        }
      }
    }

    def waitForVariablesToChange(
      watchedVariables: Set[ScopedRemoteVariableName],
      watchPosition: Index
    ): ZIO[Any, ExecutorError, Timestamp] =
      durableLog
        .subscribe(
          Topics.variableChanges(initialState.scope.rootScope.flowId),
          watchPosition
        )
        .mapBoth(
          ExecutorError.LogError,
          raw => execEnv.deserializer.deserialize[ScopedRemoteVariableName](raw)
        )
        .collect {
          case Right(scopedName) if watchedVariables.contains(scopedName) => scopedName
        }
        .runHead
        .flatMap {
          case Some(scopedName) =>
            remoteVariableKvStore
              .getLatestTimestamp(scopedName.name, scopedName.scope)
              .flatMap {
                case Some((timestamp, _)) => ZIO.succeed(timestamp)
                case None =>
                  ZIO.fail(
                    ExecutorError.MissingVariable(scopedName.name, "getting latest timestamp of changed variable")
                  )
              }
          case None => ZIO.fail(ExecutorError.VariableChangeLogFinished)
        }

    def commandToStateChange(command: ExecutionCommand): StateChange =
      command match {
        case ExecutionCommand.Pause  => StateChange.pause
        case ExecutionCommand.Resume => StateChange.resume(resetWatchedVariables = false)
      }

    def processMessages(messages: Queue[PersistentExecutor.ExecutionCommand]): ZIO[Any, Nothing, StateChange] =
      messages.takeAll.map { commands =>
        commands.foldLeft(StateChange.none) { case (s, cmd) =>
          s ++ commandToStateChange(cmd)
        }
      }

    def waitAndProcessMessages(messages: Queue[PersistentExecutor.ExecutionCommand]): ZIO[Any, Nothing, StateChange] =
      messages.take.flatMap { first =>
        processMessages(messages).map { rest =>
          commandToStateChange(first) ++ rest
        }
      }

    def runSteps(
      stateRef: Ref[State[E, A]],
      messages: Queue[ExecutionCommand],
      executionStartedAt: OffsetDateTime
    ): ZIO[
      VirtualClock with KeyValueStore with ExecutionEnvironment with DurableLog with RemoteVariableKeyValueStore,
      ExecutorError,
      Unit
    ] =
      stateRef.get.flatMap { lastState =>
        ZIO.logAnnotate("flowId", lastState.id.asString) {
          Logging
            .optionalTransactionId(lastState.transactionStack.headOption.map(_.id)) {
              processMessages(messages).flatMap { changesFromMessages =>
                val scope         = lastState.scope
                val remoteContext = ZLayer(PersistentRemoteContext.make(scope))

                remoteContext {
                  for {
                    recordingContext <- RecordingRemoteContext.startRecording

                    state0 <-
                      persistState(
                        lastState,
                        changesFromMessages,
                        recordingContext
                      ).map(_.asInstanceOf[PersistentExecutor.State[E, A]])

                    stepResult <-
                      state0.status match {
                        case FlowStatus.Running =>
                          step(state0).provideSomeLayer[
                            VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog
                          ](
                            ZLayer.succeed(recordingContext.remoteContext)
                          )
                        case FlowStatus.Done =>
                          ZIO.succeed(StepResult(StateChange.none, continue = false))
                        case FlowStatus.Suspended =>
                          waitForVariablesToChange(state0.watchedVariables, state0.watchPosition.next).flatMap {
                            timestamp =>
                              Clock.currentDateTime.flatMap { now =>
                                val suspendedDuration = Duration.between(state0.suspendedAt.getOrElse(now), now)
                                metrics.flowSuspendedTime
                                  .update(suspendedDuration)
                                  .as(
                                    StepResult(
                                      StateChange.resume(resetWatchedVariables = true) ++ StateChange
                                        .advanceClock(atLeastTo = timestamp),
                                      continue = true
                                    )
                                  )
                              }
                          }
                        case FlowStatus.Paused =>
                          ZIO.logInfo(s"Flow paused, waiting for resume command") *>
                            waitAndProcessMessages(messages).map { changes =>
                              StepResult(changes, continue = true)
                            }
                      }
                    now <- Clock.currentDateTime
                    state2 <-
                      persistState(
                        state0,
                        stepResult.stateChange ++ StateChange.updateCurrentExecutionTime(now, executionStartedAt),
                        recordingContext
                      )
                    _ <- stateRef.set(state2.asInstanceOf[PersistentExecutor.State[E, A]])
                  } yield stepResult
                }
              }.flatMap { stepResult =>
                runSteps(stateRef, messages, executionStartedAt).when(stepResult.continue).unit
              }
            }
        }
      }

    // run()
    for {
      ref       <- Ref.make[State[E, A]](initialState)
      startGate <- Promise.make[Nothing, Unit]
      now       <- Clock.currentDateTime
      messages  <- Queue.unbounded[ExecutionCommand]

      fiber <- {
        for {
          _ <- startGate.await
          _ <- {
            for {
              state <- saveStateChange(
                         initialState.id.asFlowId,
                         initialState,
                         StateChange.resetExecutionTime,
                         initialState.lastTimestamp
                       )
              _ <- ref.set(state)
              _ <- runSteps(ref, messages, executionStartedAt = now)
                     .provide(
                       ZLayer.succeed(execEnv),
                       ZLayer.succeed(kvStore),
                       ZLayer.succeed(durableLog),
                       ZLayer(VirtualClock.make(state.lastTimestamp)),
                       ZLayer.succeed(remoteVariableKvStore)
                     ) @@ metrics.executorErrorCount
            } yield ()
          }.catchAll { error =>
            for {
              _ <- ZIO.logErrorCause(s"Persistent executor ${initialState.id} failed", Cause.fail(error))
              _ <- initialState.result
                     .fail(Left(error))
                     .provideEnvironment(promiseEnv)
                     .catchAll { error2 =>
                       ZIO.logFatal(s"Failed to serialize execution failure: $error2")
                     }
              _ <- updateFinishedFlowMetrics(
                     metrics.FlowResult.Death,
                     initialState.startedAt,
                     initialState.totalExecutionTime
                   )
            } yield ()
          }
        } yield ()
      }.ensuring {
        workflows.delete(initialState.id.asFlowId).commit *> updateWorkflowMetrics()
      }.fork
      runtimeState = PersistentExecutor.RuntimeState(
                       result = initialState.result,
                       fiber = fiber,
                       executionStartedAt = now,
                       state = ref.asInstanceOf[Ref[State[_, _]]],
                       messages = messages
                     )
      _ <- promise.succeed(runtimeState)
      _ <- startGate.succeed(())
    } yield initialState.result
  }

  private def commitModifiedVariablesToParent(
    transactionState: TransactionState,
    currentContext: RemoteContext,
    targetContext: RemoteContext
  ): ZIO[Any, ExecutorError, Boolean] =
    ZIO.logDebug(s"Committing transaction ${transactionState.id}") *>
      ZIO
        .foreachDiscard(transactionState.accessedVariables) { case (name, access) =>
          targetContext
            .getLatestTimestamp(name)
            .mapError(Some(_))
            .flatMap { optLatestTimestamp =>
              val latestTimestamp = optLatestTimestamp.getOrElse(Timestamp(0L))
              if (latestTimestamp > access.previousTimestamp) {
                ZIO.logWarning(
                  s"Variable ${name} changed outside the transaction ${transactionState.id}; latest: $latestTimestamp previous: ${access.previousTimestamp}"
                ) *>
                  ZIO.fail(None)
              } else {
                currentContext
                  .getVariable(name)
                  .flatMap {
                    case Some(value) =>
                      ZIO.logDebug(
                        s"Committing modified value for variable ${name}: ${value} (latestTimestamp: $latestTimestamp; recorded access: $access"
                      ) *>
                        targetContext.setVariable(name, value)
                    case None =>
                      ZIO.fail(ExecutorError.MissingVariable(name, s"transaction ${transactionState.id}"))
                  }
                  .mapError(Some(_))
              }
            }
            .when(access.wasModified)
        }
        .unsome
        .map(_.isDefined)
        .ensuring(ZIO.logDebug(s"Finished committing transaction ${transactionState.id}"))

  private def persistState(
    state0: PersistentExecutor.State[_, _],
    stateChange: PersistentExecutor.StateChange,
    recordingContext: RecordingRemoteContext
  ): ZIO[
    RemoteContext with VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog,
    ExecutorError,
    PersistentExecutor.State[_, _]
  ] = {
    // TODO: optimization: do not persist state if there were no side effects
    val state1 = stateChange(state0)
    for {
      _                    <- recordingContext.virtualClock.advance(state1.lastTimestamp)
      modifiedVariables    <- recordingContext.getModifiedVariables
      readVariables        <- RemoteVariableKeyValueStore.getReadVariables
      currentTimestamp     <- recordingContext.virtualClock.current
      modifiedVariableNames = modifiedVariables.map(_._1).toSet

      readVariablesWithTimestamps <-
        state1.scope.parentScope match {
          case Some(parentScope) =>
            PersistentRemoteContext.make(parentScope).flatMap { parentContext =>
              ZIO
                .foreach(Chunk.fromIterable(readVariables.map(_.name))) { name =>
                  val wasModified = modifiedVariableNames.contains(name)
                  parentContext.getLatestTimestamp(name).map { ts =>
                    val finalTs = ts.map(ts => if (ts < currentTimestamp) ts else currentTimestamp)
                    (name, finalTs, wasModified)
                  }
                }
            }
          case None =>
            ZIO.succeed(Chunk.empty)
        }

      _ <-
        ZIO
          .logInfo(
            s"Persisting changes to ${modifiedVariables.size} remote variables"
          )
          .when(modifiedVariables.nonEmpty)

      remoteContext = recordingContext.commitContext
      _ <- ZIO.foreachDiscard(modifiedVariables) { case (name, value) =>
             remoteContext.setVariable(name, value)
           }
      lastIndex <- RemoteVariableKeyValueStore.getLatestIndex

      additionalStateChanges =
        StateChange.updateLastTimestamp(currentTimestamp) ++
          StateChange.updateWatchPosition(Index(state1.watchPosition.max(lastIndex))) ++
          StateChange.recordAccessedVariables(readVariablesWithTimestamps) ++
          StateChange.recordReadVariables(readVariables)

      state2 = additionalStateChanges(state1)
      _     <- saveStateChange(state2.id.asFlowId, state0, stateChange ++ additionalStateChanges, currentTimestamp)
    } yield state2
  }

  private def loadState(id: FlowId): IO[ExecutorError, Option[PersistentExecutor.State[_, _]]] =
    for {
      _  <- ZIO.logInfo(s"Looking for persisted flow state $id")
      key = id.toRaw
      persistedState <- kvStore
                          .getLatest(Namespaces.workflowState, key, None)
                          .mapError(ExecutorError.KeyValueStoreError("getLatest", _))
      state <- persistedState match {
                 case Some(bytes) =>
                   ZIO.logInfo(s"Using persisted state (${bytes.size} bytes)") *>
                     ZIO
                       .fromEither(execEnv.deserializer.deserialize[PersistentExecutor.State[Any, Any]](bytes))
                       .mapBoth(ExecutorError.DeserializationError(s"state of $id", _), Some(_))
                 case None => ZIO.logInfo("No persisted state available").as(None)
               }
    } yield state

  private def saveStateChange[E, A](
    id: FlowId,
    baseState: State[E, A],
    changes: StateChange,
    currentTimestamp: Timestamp
  ): ZIO[Any, ExecutorError.KeyValueStoreError, State[E, A]] =
    for {
      updatedState  <- ZIO.succeed(changes(baseState))
      persistedState = execEnv.serializer.serialize(updatedState)
      key            = id.toRaw
      _             <- metrics.serializedFlowStateSize.update(persistedState.size)
      _ <- kvStore
             .put(Namespaces.workflowState, key, persistedState, currentTimestamp)
             .mapError(ExecutorError.KeyValueStoreError("put", _))
    } yield updatedState

  private def interruptFlow(id: FlowId): ZIO[Any, ExecutorError, Boolean] =
    for {
      _     <- ZIO.log(s"Interrupting flow $id")
      state <- workflows.get(id).commit
      result <- state match {
                  case Some(runtimeStatePromise) =>
                    for {
                      runtimeState <- runtimeStatePromise.await
                      _            <- runtimeState.fiber.interrupt
                      _ <- loadState(id).flatMap {
                             case None => ZIO.unit
                             case Some(lastState) =>
                               saveStateChange(id, lastState, StateChange.done, lastState.lastTimestamp)
                           }
                      _ <- runtimeState.result.fail(Left(ExecutorError.Interrupted)).provideEnvironment(promiseEnv)
                    } yield true
                  case None =>
                    ZIO.succeed(false)
                }
    } yield result

  private def getAllReferences(name: ScopedRemoteVariableName): ZIO[Any, ExecutorError, Set[ScopedRemoteVariableName]] =
    // NOTE: this could be optimized if we store some type information and only read variables that are known to be remote or flow
    ZIO.logDebug(s"Garbage collector checking $name") *>
      remoteVariableKvStore
        .getLatest(name.name, name.scope, before = None)
        .flatMap {
          case Some((bytes, scope)) =>
            ZIO
              .fromEither(execEnv.deserializer.deserialize[DynamicValue](bytes))
              .map { dynValue =>
                val remote = Remote.fromDynamic(dynValue)
                remote.variableUsage.variables.map(name => ScopedRemoteVariableName(name, scope)) + name
              }
              .catchAll(_ => ZIO.succeed(Set(name)))
          case None =>
            ZIO.succeed(Set(name))
        }

  private def recursiveGetReferencedVariables(
    allStoredVariables: Set[ScopedRemoteVariableName],
    topLevelVariables: Set[ScopedRemoteVariableName],
    variables: Set[ScopedRemoteVariableName],
    alreadyRead: Set[ScopedRemoteVariableName]
  ): ZIO[Any, ExecutorError, Set[ScopedRemoteVariableName]] = {
    def withAllParents(name: ScopedRemoteVariableName): Set[ScopedRemoteVariableName] =
      name.scope.parentScope match {
        case Some(parent) => withAllParents(ScopedRemoteVariableName(name.name, parent)) + name
        case None         => Set(name)
      }

    val allTransactionalVariables = allStoredVariables.collect {
      case name @ ScopedRemoteVariableName(_, RemoteVariableScope.Transactional(_, _)) => name
    }
    val possibleTransactionalVariables = topLevelVariables.flatMap { name =>
      allTransactionalVariables.filter(_.scope.flowId == name.scope.flowId)
    }
    val variablesAndTheirParents = variables.flatMap(withAllParents) intersect allStoredVariables

    val allVariables = variablesAndTheirParents union possibleTransactionalVariables
    val newVariables = allVariables.diff(alreadyRead)

    ZIO.foreach(newVariables)(scopedVariable => getAllReferences(scopedVariable)).flatMap { extendedNewVariables =>
      val finalAllVariables = allVariables union extendedNewVariables.flatten
      if (finalAllVariables.size > allVariables.size) {
        recursiveGetReferencedVariables(
          allStoredVariables,
          topLevelVariables,
          finalAllVariables,
          alreadyRead union newVariables
        )
      } else {
        ZIO.succeed(finalAllVariables)
      }
    }
  }

  private[flow] def startGarbageCollector(): ZIO[Scope, Nothing, Unit] =
    ZStream.fromQueue(gcQueue).mapZIO(cmd => garbageCollect(cmd.finished)).runDrain.forkScoped.unit

  private def garbageCollect(finished: Promise[Nothing, Any]): ZIO[Any, Nothing, Unit] = {
    for {
      _                  <- ZIO.logInfo(s"Garbage Collection starting")
      allStoredVariables <- remoteVariableKvStore.allStoredVariables.runCollect.map(_.toSet)
      allWorkflows       <- workflows.keys.commit
      allStates          <- ZIO.foreach(allWorkflows)(loadState).map(_.flatten)
      allTopLevelReferencedVariables =
        allStates.foldLeft(Set.empty[ScopedRemoteVariableName]) { case (vars, state) =>
          state.current.variableUsage
            .unionAll(
              state.envStack.map(_.variableUsage)
            )
            .unionAll(
              state.stack.map {
                case Instruction.PopEnv       => VariableUsage.none
                case Instruction.PushEnv(env) => env.variableUsage
                case Instruction.Continuation(onError, onSuccess) =>
                  onError.variableUsage.union(onSuccess.variableUsage)
                case Instruction.CaptureRetry(onRetry) => onRetry.variableUsage
                case Instruction.CommitTransaction     => VariableUsage.none
              }
            )
            .variables
            .map(name => ScopedRemoteVariableName(name, state.id.asScope)) union vars
        }
      allReferencedVariables <- recursiveGetReferencedVariables(
                                  allStoredVariables = allStoredVariables,
                                  topLevelVariables = allTopLevelReferencedVariables,
                                  variables = allTopLevelReferencedVariables,
                                  alreadyRead = Set.empty
                                )
      unusedVariables = allStoredVariables.diff(allReferencedVariables)
      _ <-
        ZIO.logDebug(
          s"Garbage collector deletes the following unreferenced variables: ${unusedVariables.map(_.asString).mkString(", ")}"
        )
      _ <- ZIO.foreachDiscard(unusedVariables) { scopedVar =>
             remoteVariableKvStore.delete(scopedVar.name, scopedVar.scope) @@ metrics.gcDeletions
           }
      _ <- ZIO.logInfo(s"Garbage Collection finished")
    } yield ()
  }.catchAllCause { cause =>
    ZIO.logErrorCause(s"Garbage collection failed", cause)
  }.ensuring(finished.succeed(())) @@ metrics.gcTimeMillis @@ metrics.gcRuns

  private def updateWorkflowMetrics(): ZIO[Any, Nothing, Unit] =
    for {
      promises <- workflows.values.commit
      getRts   <- ZIO.foreach(promises)(_.poll).map(_.flatten)
      rts      <- ZIO.collectAll(getRts)
      statuses <- ZIO.collectAll(rts.map(_.state.get))
      byStatus  = statuses.groupBy(_.status)
      _ <- metrics
             .activeFlows(FlowStatus.Running)
             .set(byStatus.getOrElse(FlowStatus.Running, List.empty).length)
      _ <- metrics
             .activeFlows(FlowStatus.Suspended)
             .set(byStatus.getOrElse(FlowStatus.Suspended, List.empty).length)
      _ <- metrics
             .activeFlows(FlowStatus.Paused)
             .set(byStatus.getOrElse(FlowStatus.Paused, List.empty).length)
    } yield ()

  private def updateFinishedFlowMetrics(
    result: metrics.FlowResult,
    startedAt: OffsetDateTime,
    totalExecutionTime: Duration
  ): ZIO[Any, Nothing, Unit] =
    for {
      _   <- finishedFlowCount(result).increment
      now <- Clock.currentDateTime
      age  = Duration.between(startedAt, now)
      _   <- finishedFlowAge(result).update(age)
      _   <- flowTotalExecutionTime(result).update(totalExecutionTime)
    } yield ()
}

object PersistentExecutor {
  def make(
    gcPeriod: Duration = 5.minutes
  ): ZLayer[
    DurableLog with KeyValueStore with Configuration with OperationExecutor with Serializer with Deserializer,
    Nothing,
    ZFlowExecutor
  ] =
    ZLayer {
      for {
        configuration <- ZIO.service[Configuration]
        serializer    <- ZIO.service[Serializer]
        deserializer  <- ZIO.service[Deserializer]
      } yield ExecutionEnvironment(serializer, deserializer, configuration, gcPeriod)
    } >+> (DurableLog.any ++ KeyValueStore.any ++ OperationExecutor.any ++ RemoteVariableKeyValueStore.layer) >>> layer

  val layer: ZLayer[
    DurableLog with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with OperationExecutor,
    Nothing,
    ZFlowExecutor
  ] =
    ZLayer.scoped {
      for {
        durableLog            <- ZIO.service[DurableLog]
        kvStore               <- ZIO.service[KeyValueStore]
        remoteVariableKvStore <- ZIO.service[RemoteVariableKeyValueStore]
        operationExecutor     <- ZIO.service[OperationExecutor]
        execEnv               <- ZIO.service[ExecutionEnvironment]
        workflows             <- TMap.empty[FlowId, Promise[Nothing, PersistentExecutor.RuntimeState]].commit
        gcQueue               <- Queue.bounded[GarbageCollectionCommand](1)
        _ <- Promise
               .make[Nothing, Any]
               .flatMap(finished => gcQueue.offer(GarbageCollectionCommand(finished)))
               .scheduleFork(Schedule.fixed(execEnv.gcPeriod))
        executor = PersistentExecutor(
                     execEnv,
                     durableLog,
                     kvStore,
                     remoteVariableKvStore,
                     operationExecutor,
                     workflows,
                     gcQueue
                   )
        _ <- executor.startGarbageCollector()
      } yield executor
    }

  sealed trait Instruction

  object Instruction {
    case object PopEnv extends Instruction

    final case class PushEnv(env: Remote[_]) extends Instruction

    final case class Continuation[R, A, E, E2, B](
      onError: UnboundRemoteFunction[E, ZFlow[R, E2, B]],
      onSuccess: UnboundRemoteFunction[A, ZFlow[R, E2, B]]
    ) extends Instruction {

      override def toString: String =
        s"Continuation(\n  onError: $onError\n  onSuccess: $onSuccess\n)\n"
    }

    final case class CaptureRetry[R, E, A](onRetry: ZFlow[R, E, A]) extends Instruction

    case object CommitTransaction extends Instruction

    implicit val schema: Schema[Instruction] =
      Schema.EnumN(
        TypeId.parse("zio.flow.runtime.internal.PersistentExecutor.Instruction"),
        CaseSet
          .Cons(
            Schema.Case[PopEnv.type, Instruction]("PopEnv", Schema.singleton(PopEnv), _.asInstanceOf[PopEnv.type]),
            CaseSet.Empty[Instruction]()
          )
          .:+:(
            Schema.Case[PushEnv, Instruction](
              "PushEnv",
              Remote.schemaAny.transform(PushEnv.apply, _.env),
              _.asInstanceOf[PushEnv]
            )
          )
          .:+:(
            Schema.Case[Continuation[Any, Any, Any, Any, Any], Instruction](
              "Continuation",
              Schema.CaseClass2[UnboundRemoteFunction[Any, ZFlow[Any, Any, Any]], UnboundRemoteFunction[
                Any,
                ZFlow[Any, Any, Any]
              ], Continuation[Any, Any, Any, Any, Any]](
                TypeId.parse("zio.flow.runtime.internal.PersistentExecutor.Instruction.Continuation"),
                Schema.Field("onError", UnboundRemoteFunction.schema[Any, ZFlow[Any, Any, Any]]),
                Schema.Field("onSuccess", UnboundRemoteFunction.schema[Any, ZFlow[Any, Any, Any]]),
                Continuation(_, _),
                _.onError,
                _.onSuccess
              ),
              _.asInstanceOf[Continuation[Any, Any, Any, Any, Any]]
            )
          )
          .:+:(
            Schema.Case[CaptureRetry[Any, Any, Any], Instruction](
              "CaptureRetry",
              ZFlow.schemaAny.transform(
                CaptureRetry(_),
                _.onRetry
              ),
              _.asInstanceOf[CaptureRetry[Any, Any, Any]]
            )
          )
          .:+:(
            Schema.Case[CommitTransaction.type, Instruction](
              "CommitTransaction",
              Schema.singleton(CommitTransaction),
              _.asInstanceOf[CommitTransaction.type]
            )
          )
      )
  }

  case class GarbageCollectionCommand(finished: Promise[Nothing, Any])

  case class StepResult(stateChange: StateChange, continue: Boolean)

  sealed trait StateChange { self =>
    def ++(otherChange: StateChange): StateChange =
      (self, otherChange) match {
        case (StateChange.SequentialChange(changes1), StateChange.SequentialChange(changes2)) =>
          StateChange.SequentialChange(changes1 ++ changes2)
        case (StateChange.SequentialChange(changes1), _) =>
          StateChange.SequentialChange(changes1 :+ otherChange)
        case (_, StateChange.SequentialChange(changes2)) =>
          StateChange.SequentialChange(self +: changes2)
        case _ =>
          StateChange.SequentialChange(Chunk(self, otherChange))
      }

    def apply[E, A](state: State[E, A]): State[E, A]
  }
  object StateChange {
    private case object NoChange extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] = state
    }
    private final case class SequentialChange(changes: Chunk[StateChange]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] = changes.foldLeft(state) { case (state, change) =>
        change(state)
      }
    }
    private final case class SetCurrent(current: ZFlow[_, _, _]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(current = current)
    }
    private final case class PushContinuation(cont: Instruction) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(stack = cont :: state.stack)
    }
    private final case object PopContinuation extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(stack = state.stack.tail)
    }
    private final case class AddCompensation(newCompensation: ZFlow[Any, ActivityError, Unit]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(
          transactionStack = state.transactionStack match {
            case ::(head, next) =>
              head.copy(compensations = newCompensation :: head.compensations) :: next
            case Nil =>
              Nil
          }
        )
    }
    private final case class EnterTransaction(flow: ZFlow[Any, _, _]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] = {
        val transactionId = TransactionId.fromCounter(state.transactionCounter)
        state.copy(
          transactionStack = TransactionState(
            transactionId,
            compensations = Nil,
            accessedVariables = Map.empty,
            readVariables = Set.empty,
            body = flow
          ) :: state.transactionStack,
          transactionCounter = state.transactionCounter + 1
        )
      }
    }
    private case object LeaveTransaction extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(
          transactionStack = state.transactionStack.tail
        )
    }
    private case class RevertCurrentTransaction[E0](failure: Remote[E0]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.transactionStack.headOption match {
          case Some(txState) =>
            val compensations = txState.compensations.foldLeft[ZFlow[Any, ActivityError, Unit]](ZFlow.unit)(_ *> _)
            val compensateAndFail: ZFlow[_, _, _] =
              ZFlow.Fold(
                compensations,
                None,
                Some(
                  UnboundRemoteFunction.make((_: Remote[Unit]) =>
                    ZFlow.Fail(failure).asInstanceOf[ZFlow[Any, Any, Any]]
                  )
                )
              )
            state.copy(
              current = compensateAndFail
            )
          case None => state
        }
    }
    private final case class RestartCurrentTransaction(suspend: Boolean, now: OffsetDateTime) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.transactionStack.headOption match {
          case Some(txState) =>
            val compensations = txState.compensations.foldLeft[ZFlow[Any, ActivityError, Unit]](ZFlow.unit)(_ *> _)
            val compensateAndRun: ZFlow[_, _, _] =
              ZFlow.Fold(
                compensations,
                None,
                Some(UnboundRemoteFunction.make((_: Remote[Unit]) => txState.body.asInstanceOf[ZFlow[Any, Any, Any]]))
              )

            // We need to assign a new transaction ID because we are not cleaning up persisted variables immediately
            val newTransactionId = TransactionId.fromCounter(state.transactionCounter)
            state.copy(
              current = compensateAndRun,
              transactionStack = txState.copy(
                id = newTransactionId,
                accessedVariables = Map.empty,
                readVariables = Set.empty
              ) :: state.transactionStack.tail,
              status = if (suspend) FlowStatus.Suspended else FlowStatus.Running,
              watchedVariables = txState.readVariables,
              transactionCounter = state.transactionCounter + 1,
              suspendedAt = if (suspend) Some(now) else None
            )
          case None => state
        }
    }
    private case object IncreaseForkCounter extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(forkCounter = state.forkCounter + 1)
    }
    private case object IncreaseTempVarCounter extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(tempVarCounter = state.tempVarCounter + 1)
    }
    private final case class PushEnvironment(value: Remote[_]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(envStack = value :: state.envStack)
    }
    private final case object PopEnvironment extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(envStack = state.envStack.tail)
    }
    private final case class AdvanceClock(atLeastTo: Timestamp) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(lastTimestamp = state.lastTimestamp.max(atLeastTo))
    }
    private case object Done extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(status = FlowStatus.Done)
    }
    private case class Resume(resetWatchedVariables: Boolean) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(
          status = FlowStatus.Running,
          watchedVariables = if (resetWatchedVariables) Set.empty else state.watchedVariables
        )
    }
    private case object Pause extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(status = FlowStatus.Paused)
    }
    private final case class UpdateWatchPosition(newWatchPosition: Index) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(
          watchPosition = Index(Math.max(state.watchPosition, newWatchPosition))
        )
    }

    private final case class UpdateCurrentExecutionTime(now: OffsetDateTime, executionStartedAt: OffsetDateTime)
        extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(
          currentExecutionTime = Duration.between(executionStartedAt, now)
        )
    }

    private final case class RecordAccessedVariables(variables: Seq[(RemoteVariableName, Option[Timestamp], Boolean)])
        extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.transactionStack match {
          case currentTransaction :: rest =>
            state.copy(
              transactionStack =
                currentTransaction.copy(accessedVariables = variables.foldLeft(currentTransaction.accessedVariables) {
                  case (result, (name, timestamp, wasModified)) =>
                    result.get(name) match {
                      case Some(access) =>
                        result + (name -> access.copy(wasModified = access.wasModified || wasModified))
                      case None =>
                        // First time this variable is modified in this transaction
                        result + (name -> RecordedAccess(
                          previousTimestamp = timestamp.getOrElse(Timestamp(0L)),
                          wasModified
                        ))
                    }
                }) :: rest
            )
          case Nil =>
            state
        }
    }

    private final case class RecordReadVariables(variables: Set[ScopedRemoteVariableName]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.transactionStack match {
          case currentTransaction :: rest =>
            state.copy(
              transactionStack =
                currentTransaction.copy(readVariables = currentTransaction.readVariables union variables) :: rest
            )
          case Nil =>
            state
        }
    }

    private final case class UpdateLastTimestamp(timestamp: Timestamp) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(lastTimestamp = timestamp)
    }

    private case object ResetExecutionTime extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(
          totalExecutionTime = state.totalExecutionTime + state.currentExecutionTime,
          currentExecutionTime = Duration.ZERO
        )
    }

    val none: StateChange                                = NoChange
    def setCurrent(current: ZFlow[_, _, _]): StateChange = SetCurrent(current)
    def pushContinuation(cont: Instruction): StateChange = PushContinuation(cont)
    def popContinuation: StateChange                     = PopContinuation
    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Unit]): StateChange = AddCompensation(
      newCompensation
    )
    def enterTransaction(flow: ZFlow[Any, _, _]): StateChange        = EnterTransaction(flow)
    val leaveTransaction: StateChange                                = LeaveTransaction
    def revertCurrentTransaction[E](failure: Remote[E]): StateChange = RevertCurrentTransaction(failure)
    def restartCurrentTransaction(suspend: Boolean, now: OffsetDateTime): StateChange =
      RestartCurrentTransaction(suspend, now)
    val increaseForkCounter: StateChange                    = IncreaseForkCounter
    val increaseTempVarCounter: StateChange                 = IncreaseTempVarCounter
    def pushEnvironment(value: Remote[_]): StateChange      = PushEnvironment(value)
    def popEnvironment: StateChange                         = PopEnvironment
    def advanceClock(atLeastTo: Timestamp): StateChange     = AdvanceClock(atLeastTo)
    val done: StateChange                                   = Done
    def resume(resetWatchedVariables: Boolean): StateChange = Resume(resetWatchedVariables)
    val pause: StateChange                                  = Pause
    def updateWatchPosition(index: Index): StateChange      = UpdateWatchPosition(index)
    def updateCurrentExecutionTime(now: OffsetDateTime, executionStartedAt: OffsetDateTime): StateChange =
      UpdateCurrentExecutionTime(now, executionStartedAt)
    def recordAccessedVariables(variables: Seq[(RemoteVariableName, Option[Timestamp], Boolean)]): StateChange =
      RecordAccessedVariables(variables)
    def recordReadVariables(variables: Set[ScopedRemoteVariableName]): StateChange =
      RecordReadVariables(variables)
    def updateLastTimestamp(timestamp: Timestamp): StateChange =
      UpdateLastTimestamp(timestamp)
    val resetExecutionTime: StateChange = ResetExecutionTime
  }

  final case class FlowResult(result: DynamicValue, timestamp: Timestamp)
  object FlowResult {
    implicit val schema: Schema[FlowResult] = DeriveSchema.gen
  }

  final case class State[E, A](
    id: ScopedFlowId,
    lastTimestamp: Timestamp,
    current: ZFlow[_, _, _],
    stack: List[Instruction],
    result: DurablePromise[Either[ExecutorError, DynamicValue], FlowResult],
    envStack: List[Remote[_]],
    transactionStack: List[TransactionState],
    tempVarCounter: Int,
    promiseIdCounter: Int,
    forkCounter: Int,
    transactionCounter: Int,
    status: FlowStatus,
    watchedVariables: Set[ScopedRemoteVariableName],
    watchPosition: Index,
    startedAt: OffsetDateTime,
    suspendedAt: Option[OffsetDateTime],
    totalExecutionTime: Duration,
    currentExecutionTime: Duration
  ) {

    def currentEnvironment: Remote[_] = envStack.headOption.getOrElse(
      Remote.unit
    )

    def isInTransaction: Boolean = transactionStack.nonEmpty

    def scope: RemoteVariableScope =
      transactionStack match {
        case ::(head, next) =>
          RemoteVariableScope.Transactional(
            this.copy(transactionStack = next).scope,
            head.id
          )
        case Nil =>
          id.asScope
      }
  }

  object State {
    implicit def schema[E, A]: Schema[State[E, A]] =
      Schema.CaseClass18(
        TypeId.parse("zio.flow.runtime.internal.PersistentExecutor.State"),
        Schema.Field("id", Schema[ScopedFlowId]),
        Schema.Field("lastTimestamp", Schema[Timestamp]),
        Schema.Field("current", ZFlow.schemaAny),
        Schema.Field("stack", Schema[List[Instruction]]),
        Schema.Field("result", Schema[DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]]),
        Schema.Field("envStack", Schema[List[Remote[_]]]),
        Schema.Field("transactionStack", Schema[List[TransactionState]]),
        Schema.Field("tempVarCounter", Schema[Int]),
        Schema.Field("promiseIdCounter", Schema[Int]),
        Schema.Field("forkCounter", Schema[Int]),
        Schema.Field("transactionCounter", Schema[Int]),
        Schema.Field("status", Schema[FlowStatus]),
        Schema.Field("watchedVariables", Schema[Set[ScopedRemoteVariableName]]),
        Schema.Field("watchPosition", Schema[Index]),
        Schema.Field("startedAt", Schema[OffsetDateTime]),
        Schema.Field("suspendedAt", Schema[Option[OffsetDateTime]]),
        Schema.Field("totalExecutionTime", Schema[Duration]),
        Schema.Field("currentExecutionTime", Schema[Duration]),
        (
          id: ScopedFlowId,
          lastTimestamp: Timestamp,
          current: ZFlow[_, _, _],
          stack: List[Instruction],
          result: DurablePromise[Either[ExecutorError, DynamicValue], FlowResult],
          envStack: List[Remote[_]],
          transactionStack: List[TransactionState],
          tempVarCounter: Int,
          promiseIdCounter: Int,
          forkCounter: Int,
          transactionCounter: Int,
          status: FlowStatus,
          watchedVariables: Set[ScopedRemoteVariableName],
          watchPosition: Index,
          startedAt: OffsetDateTime,
          suspendedAt: Option[OffsetDateTime],
          totalExecutionTime: Duration,
          currentExecutionTime: Duration
        ) =>
          State(
            id,
            lastTimestamp,
            current,
            stack,
            result,
            envStack,
            transactionStack,
            tempVarCounter,
            promiseIdCounter,
            forkCounter,
            transactionCounter,
            status,
            watchedVariables,
            watchPosition,
            startedAt,
            suspendedAt,
            totalExecutionTime,
            currentExecutionTime
          ),
        _.id,
        _.lastTimestamp,
        _.current.asInstanceOf[ZFlow[Any, Any, Any]],
        _.stack,
        _.result,
        _.envStack,
        _.transactionStack,
        _.tempVarCounter,
        _.promiseIdCounter,
        _.forkCounter,
        _.transactionCounter,
        _.status,
        _.watchedVariables,
        _.watchPosition,
        _.startedAt,
        _.suspendedAt,
        _.totalExecutionTime,
        _.currentExecutionTime
      )
  }

  final case class RecordedAccess(previousTimestamp: Timestamp, wasModified: Boolean)
  object RecordedAccess {
    implicit val schema: Schema[RecordedAccess] = DeriveSchema.gen
  }

  final case class TransactionState(
    id: TransactionId,
    accessedVariables: Map[RemoteVariableName, RecordedAccess],
    compensations: List[ZFlow[Any, ActivityError, Unit]],
    readVariables: Set[ScopedRemoteVariableName],
    body: ZFlow[_, _, _]
  )

  object TransactionState {
    implicit val schema: Schema[TransactionState] =
      Schema.CaseClass5(
        TypeId.parse("zio.flow.runtime.internal.PersistentExecutor.TransactionState"),
        Schema.Field("id", Schema[TransactionId]),
        Schema.Field("accessedVariables", Schema[Map[RemoteVariableName, RecordedAccess]]),
        Schema.Field("compensations", Schema[List[ZFlow[Any, ActivityError, Unit]]]),
        Schema.Field("readVariables", Schema[Set[ScopedRemoteVariableName]]),
        Schema.Field("body", ZFlow.schemaAny),
        (
          id: TransactionId,
          accessedVariables: Map[RemoteVariableName, RecordedAccess],
          compensations: List[ZFlow[Any, ActivityError, Unit]],
          readVariables: Set[ScopedRemoteVariableName],
          body: ZFlow[_, _, _]
        ) => TransactionState(id, accessedVariables, compensations, readVariables, body),
        _.id,
        _.accessedVariables,
        _.compensations,
        _.readVariables,
        _.body.asInstanceOf[ZFlow[Any, Any, Any]]
      )
  }

  sealed trait ExecutionCommand
  object ExecutionCommand {
    case object Pause  extends ExecutionCommand
    case object Resume extends ExecutionCommand
  }

  final case class RuntimeState(
    result: DurablePromise[Either[ExecutorError, DynamicValue], FlowResult],
    fiber: Fiber[Nothing, Unit],
    executionStartedAt: OffsetDateTime,
    state: Ref[State[_, _]],
    messages: Queue[ExecutionCommand]
  )
}
