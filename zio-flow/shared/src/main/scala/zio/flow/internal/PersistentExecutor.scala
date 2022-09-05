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

package zio.flow.internal

import zio._
import zio.flow.Remote.UnboundRemoteFunction
import zio.flow.internal.IndexedStore.Index
import zio.flow.metrics
import zio.flow.remote.DynamicValueHelpers
import zio.flow.serialization._
import zio.flow._
import zio.flow.internal.PersistentExecutor.GarbageCollectionCommand
import zio.flow.metrics.{TransactionOutcome, finishedFlowAge, finishedFlowCount, flowTotalExecutionTime}
import zio.schema.{CaseSet, DeriveSchema, DynamicValue, Schema}

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.{Duration, OffsetDateTime}
import scala.annotation.nowarn
import zio.stm.{TMap, ZSTM}
import zio.stream.ZStream

// TODO: better error type than IOException
final case class PersistentExecutor(
  execEnv: ExecutionEnvironment,
  durableLog: DurableLog,
  kvStore: KeyValueStore,
  remoteVariableKvStore: RemoteVariableKeyValueStore,
  operationExecutor: OperationExecutor[Any],
  workflows: TMap[FlowId, Promise[Nothing, PersistentExecutor.RuntimeState]],
  gcQueue: Queue[GarbageCollectionCommand]
) extends ZFlowExecutor {

  import PersistentExecutor._

  private val promiseEnv = ZEnvironment(durableLog, execEnv)

  private def coerceRemote[A](remote: Remote[_]): Remote[A] = remote.asInstanceOf[Remote[A]]

  private def eval[A: Schema](remote: Remote[A]): ZIO[RemoteContext, IOException, A] =
    evalDynamic(remote).flatMap(dyn =>
      ZIO
        .fromEither(dyn.toTypedValue(implicitly[Schema[A]]))
        .mapError(msg => new IOException(s"Failed to convert remote to typed value: $msg"))
    )

  private def evalDynamic[A](remote: Remote[A]): ZIO[RemoteContext, IOException, DynamicValue] =
    (for {
      vars0 <- LocalContext.getAllVariables
      dyn   <- remote.evalDynamic
      vars1 <- LocalContext.getAllVariables
      vars   = vars1.diff(vars0)

      remote       = Remote.fromDynamic(dyn)
      usedByResult = remote.variableUsage.variables

      usedByVars <- ZIO.foldLeft(vars)(Set.empty[RemoteVariableName]) { case (set, variable) =>
                      for {
                        optDynVar <- RemoteContext.getVariable(variable.identifier)
                        result = optDynVar match {
                                   case Some(dynVar) =>
                                     val remoteVar = Remote.fromDynamic(dynVar)
                                     set union remoteVar.variableUsage.variables
                                   case None =>
                                     set
                                 }
                      } yield result
                    }
      toRemove = vars.map(_.identifier).diff(usedByResult union usedByVars)

      _ <- ZIO.foreachDiscard(toRemove)(RemoteContext.dropVariable(_))
    } yield dyn)
      .mapErrorCause(cause => cause.map(msg => new IOException(s"Failed to evaluate remote: $msg")))
      .provideSomeLayer[RemoteContext](LocalContext.inMemory)

  def submit[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): IO[E, A] =
    for {
      resultPromise <- start(ScopedFlowId.toplevel(id), Timestamp(0L), Index(0L), flow).orDie
      promiseResult <- resultPromise.awaitEither.provideEnvironment(promiseEnv).orDie
      _             <- ZIO.log(s"${FlowId.unwrap(id)} finished with $promiseResult")
      result <- promiseResult match {
                  case Left(Left(fail)) => ZIO.die(fail)
                  case Left(Right(dynamicError)) =>
                    ZIO
                      .fromEither(dynamicError.toTypedValue(Schema[E]))
                      .flatMapError(error => ZIO.die(new IOException(s"Failed to deserialize error: $error")))
                      .flatMap(success => ZIO.fail(success))
                  case Right(dynamicSuccess) =>
                    ZIO
                      .fromEither(dynamicSuccess.result.toTypedValue(Schema[A]))
                      .flatMapError(error => ZIO.die(new IOException(s"Failed to deserialize success: $error")))
                }
    } yield result

  def restartAll(): ZIO[Any, IOException, Unit] =
    for {
      deserializedStates <- kvStore
                              .scanAll(Namespaces.workflowState)
                              .mapZIO { case (rawKey, rawState) =>
                                val id = FlowId.unsafeMake(new String(rawKey.toArray, StandardCharsets.UTF_8))
                                ZIO
                                  .fromEither(
                                    execEnv.deserializer.deserialize[PersistentExecutor.State[Any, Any]](rawState)
                                  )
                                  .mapBoth(
                                    error => new IOException(s"Failed to deserialize state of $id: $error"),
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

  private def start[E, A](
    id: ScopedFlowId,
    lastTimestamp: Timestamp,
    watchPosition: Index,
    flow: ZFlow[Any, E, A]
  ): ZIO[Any, IOException, DurablePromise[Either[Throwable, DynamicValue], FlowResult]] =
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
                      DurablePromise.make[Either[Throwable, DynamicValue], FlowResult](id.asString + "_result")
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
                                   status = PersistentWorkflowStatus.Running,
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
    state: State[E, A],
    promise: Promise[_, PersistentExecutor.RuntimeState]
  ): ZIO[Any, Nothing, DurablePromise[Either[Throwable, DynamicValue], FlowResult]] = {
    import zio.flow.ZFlow._

    def step(
      state: State[E, A]
    ): ZIO[
      RemoteContext with VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog,
      IOException,
      StepResult
    ] = {

      def onSuccess(
        value: Remote[_],
        stateChange: StateChange = StateChange.none
      ): ZIO[
        VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog,
        IOException,
        StepResult
      ] = {
        val updatedState = stateChange(state)

        val scope         = updatedState.scope
        val remoteContext = ZLayer(RemoteContext.persistent(scope))
//        ZIO.logDebug(s"onSuccess in scope ${scope} with value ${value}") *>
        remoteContext {
          updatedState.stack match {
            case Nil =>
              for {
                result <- evalDynamic(value)
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
              eval(onSuccess.apply(coerceRemote(value))).map { next =>
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
                targetContext <- RemoteContext.persistent(
                                   StateChange.leaveTransaction(updatedState).scope
                                 )
                commitSucceeded <-
                  commitModifiedVariablesToParent(updatedState.transactionStack.head, currentContext, targetContext)
                result <-
                  if (commitSucceeded) {
                    for {
                      evaluatedValue <- evalDynamic(value)
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
        IOException,
        StepResult
      ] = {
        val updatedState = stateChange(state)

        val scope         = updatedState.scope
        val remoteContext = ZLayer(RemoteContext.persistent(scope))

        remoteContext {
          updatedState.stack match {
            case Nil =>
              for {
                dyn <- evalDynamic(value)
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
                  evalDynamic(value).map { evaluatedError =>
                    TransactionFailure
                      .unwrapDynamic(evaluatedError)
                      .map(unwrapped => onErrorFun.apply(Remote.Literal(unwrapped)))
                  }
                } else {
                  ZIO.some(onErrorFun.apply(coerceRemote(value)))
                }
              next.flatMap {
                case Some(next) =>
                  eval(next).map { next =>
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
                  evalDynamic(value).flatMap { evaluatedError =>
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
              evalDynamic(value).flatMap { schemaAndValue =>
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

      def failWith(error: DynamicValue, stateChange: StateChange = StateChange.none) =
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
              end     <- eval(instant)(instantSchema)
              duration = Duration.between(start, end)
              _       <- ZIO.logInfo(s"Sleeping for $duration")
              _       <- Clock.sleep(duration)
              _       <- ZIO.logInfo(s"Resuming execution after sleeping $duration")
              result  <- onSuccess(())
            } yield result

          case Read(svar) =>
            for {
              variableReference <- eval(svar)
              variable           = Remote.Variable(variableReference.name)
              stepResult        <- onSuccess(variable, StateChange.none)
            } yield stepResult

          case Modify(svar, f0) =>
            val f = f0.asInstanceOf[UnboundRemoteFunction[Any, (A, Any)]]
            for {
              variableReference <- eval(svar)
              variable           = Remote.Variable(variableReference.name)
              //            _                                      <- ZIO.debug(s"Modify: ${variable.identifier}'s previous value was $value")
              dynTuple <- evalDynamic(f(variable))
              tuple <- dynTuple match {
                         case DynamicValue.Tuple(dynResult, newValue) => ZIO.succeed((dynResult, newValue))
                         case _                                       => ZIO.fail(new IOException(s"Modify's result was not a tuple"))
                       }
              //            _                                      <- ZIO.debug(s"Modify: result is $tuple")
              (dynResult, newValue) = tuple
              _ <-
                RemoteContext.getVariable(
                  variable.identifier
                ) // NOTE: this is needed for variable access tracking to work properly, as f0 may not access the variable at all
              _ <- RemoteContext.setVariable(variable.identifier, newValue)
              //            _                                      <- ZIO.debug(s"Modify: changed value of ${variable.identifier} to $newValue")
              result = Remote.Literal(dynResult)
              stepResult <- onSuccess(
                              result,
                              StateChange.none
                            )
            } yield stepResult

          case fold @ Fold(_, _, _) =>
            val cont =
              Instruction.Continuation[fold.ValueR, fold.ValueA, fold.ValueE, fold.ValueE2, fold.ValueB](
                fold.ifError,
                fold.ifSuccess
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
              inp    <- eval(input)(activity.inputSchema)
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
              evaluatedFlow <- eval(remote)
            } yield StepResult(StateChange.setCurrent(evaluatedFlow), continue = true)

          case UnwrapRemote(remote) =>
            for {
              evaluated <- eval(coerceRemote(remote))(Remote.schemaAny)
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
                          Remote[ExecutingFlow[Any, Any]](ExecutingFlow(forkId.asFlowId, resultPromise)),
                          StateChange.increaseForkCounter
                        )
            } yield result

          case await @ Await(execFlow) =>
            for {
              executingFlow <- eval(execFlow)
              _             <- ZIO.log("Waiting for result")
              result <-
                executingFlow
                  .asInstanceOf[ExecutingFlow[Either[Throwable, await.ValueE], await.ValueA]]
                  .result
                  .asInstanceOf[DurablePromise[Either[Throwable, DynamicValue], FlowResult]]
                  .awaitEither
                  .provideEnvironment(promiseEnv)
                  .tapErrorCause(c => ZIO.log(s"Failed: $c"))
              _ <- ZIO.log(s"Await got result: $result")
              stepResult <-
                result.fold(
                  error =>
                    error
                      .fold(
                        die => ZIO.die(new IOException("Awaited flow died", die)),
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
              d <- eval(duration)
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
                                ZIO.die(new IOException("Awaited flow died", fatal))
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

          case Die => ZIO.die(new IllegalStateException("Could not evaluate ZFlow"))

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
              executingFlow          <- eval(remoteExecFlow)
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
              evalDynamic(error).flatMap { evaluatedError =>
                failWith(evaluatedError)
              }
            else
              onError(error)

          case NewVar(name, initial) =>
            for {
              initialValue <- evalDynamic(initial)
              remoteVariableName <-
                RemoteVariableName
                  .make(name)
                  .toZIO
                  .mapError(msg => new IOException(s"Failed to create remote variable with name $name: $msg"))
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
              ZFlow.recurse[Any, i.ValueE, Boolean](boolRemote) { case (continue, rec) =>
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
            eval(remoteMessage).flatMap { message =>
              ZIO.log(message) *> onSuccess(())
            }
        }
      }
    }

    def waitForVariablesToChange(
      watchedVariables: Set[ScopedRemoteVariableName],
      watchPosition: Index
    ): ZIO[Any, IOException, Timestamp] =
      durableLog
        .subscribe(
          Topics.variableChanges(state.scope.rootScope.flowId),
          watchPosition
        )
        .map { raw =>
          execEnv.deserializer.deserialize[ScopedRemoteVariableName](raw)
        }
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
                case None                 => ZIO.fail(new IOException(s"Could not get last timestamp of changed variable $scopedName"))
              }
          case None => ZIO.fail(new IOException("Variable change stream finished unexpectedly"))
        }

    def runSteps(
      stateRef: Ref[State[E, A]],
      executionStartedAt: OffsetDateTime
    ): ZIO[
      VirtualClock with KeyValueStore with ExecutionEnvironment with DurableLog with RemoteVariableKeyValueStore,
      IOException,
      Unit
    ] =
      stateRef.get.flatMap { state0 =>
        ZIO.logAnnotate("flowId", state0.id.asString) {
          Logging
            .optionalTransactionId(state0.transactionStack.headOption.map(_.id)) {
              val scope = state0.scope

              val remoteContext = ZLayer(RemoteContext.persistent(scope))

              remoteContext {
                for {
                  recordingContext <- RecordingRemoteContext.startRecording

                  stepResult <-
                    state0.status match {
                      case PersistentWorkflowStatus.Running =>
                        step(state0).provideSomeLayer[
                          VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog
                        ](
                          ZLayer.succeed(recordingContext.remoteContext)
                        )
                      case PersistentWorkflowStatus.Done =>
                        ZIO.succeed(StepResult(StateChange.none, continue = false))
                      case PersistentWorkflowStatus.Suspended =>
                        waitForVariablesToChange(state0.watchedVariables, state0.watchPosition.next).flatMap {
                          timestamp =>
                            Clock.currentDateTime.flatMap { now =>
                              val suspendedDuration = Duration.between(state0.suspendedAt.getOrElse(now), now)
                              metrics.flowSuspendedTime
                                .update(suspendedDuration)
                                .as(
                                  StepResult(
                                    StateChange.resume ++ StateChange.advanceClock(atLeastTo = timestamp),
                                    continue = true
                                  )
                                )
                            }
                        }
                    }
                  now <- Clock.currentDateTime
                  state2 <- persistState(
                              state.id.asFlowId,
                              state0,
                              stepResult.stateChange ++ StateChange.updateCurrentExecutionTime(now, executionStartedAt),
                              recordingContext
                            )
                  _ <- stateRef.set(state2.asInstanceOf[PersistentExecutor.State[E, A]])
                } yield stepResult
              }
            }
            .flatMap { stepResult =>
              runSteps(stateRef, executionStartedAt).when(stepResult.continue).unit
            }
        }
      }

    for {
      ref       <- Ref.make[State[E, A]](state)
      startGate <- Promise.make[Nothing, Unit]
      now       <- Clock.currentDateTime
      fiber <- (for {
                 _ <- startGate.await
                 _ <- runSteps(ref, executionStartedAt = now)
                        .provide(
                          ZLayer.succeed(execEnv),
                          ZLayer.succeed(kvStore),
                          ZLayer.succeed(durableLog),
                          ZLayer(VirtualClock.make(state.lastTimestamp)),
                          ZLayer.succeed(remoteVariableKvStore)
                        )
                        .catchAllCause { error =>
                          for {
                            _ <- ZIO.logErrorCause(s"Persistent executor ${state.id} failed", error)
                            _ <- state.result
                                   .fail(Left(error.squash))
                                   .provideEnvironment(promiseEnv)
                                   .absorb
                                   .catchAll { error2 =>
                                     ZIO.logFatal(s"Failed to serialize execution failure: $error2")
                                   }
                            _ <- updateFinishedFlowMetrics(
                                   metrics.FlowResult.Death,
                                   state.startedAt,
                                   state.totalExecutionTime
                                 )
                          } yield ()
                        }
                 _ <- deleteState(state.id.asFlowId).orDie
               } yield ()).ensuring {
                 workflows.delete(state.id.asFlowId).commit *> updateWorkflowMetrics()
               }.fork
      runtimeState = PersistentExecutor.RuntimeState(
                       result = state.result,
                       fiber = fiber,
                       currentStatus = PersistentWorkflowStatus.Running,
                       executionStartedAt = now
                     )
      _ <- promise.succeed(runtimeState)
      _ <- startGate.succeed(())
    } yield state.result
  }

  private def commitModifiedVariablesToParent(
    transactionState: TransactionState,
    currentContext: RemoteContext,
    targetContext: RemoteContext
  ): ZIO[Any, IOException, Boolean] =
    ZIO.logDebug(s"Committing transaction ${transactionState.id}") *>
      ZIO
        .foreachDiscard(transactionState.accessedVariables) { case (name, access) =>
          targetContext
            .getLatestTimestamp(name)
            .flatMap { optLatestTimestamp =>
              val latestTimestamp = optLatestTimestamp.getOrElse(Timestamp(0L))
              if (latestTimestamp > access.previousTimestamp) {
                ZIO.logWarning(
                  s"Variable ${name} changed outside the transaction ${transactionState.id}; latest: $latestTimestamp previous: ${access.previousTimestamp}"
                ) *>
                  ZIO.fail(None)
              } else {
                currentContext.getVariable(name).flatMap {
                  case Some(value) =>
                    ZIO.logDebug(
                      s"Committing modified value for variable ${name}: ${value} (latestTimestamp: $latestTimestamp; recorded access: $access"
                    ) *>
                      targetContext.setVariable(name, value)
                  case None =>
                    ZIO.fail(
                      Some(
                        new IOException(s"Could not read value of variable $name in transaction ${transactionState.id}")
                      )
                    )
                }
              }
            }
            .when(access.wasModified)
        }
        .unsome
        .map(_.isDefined)
        .ensuring(ZIO.logDebug(s"Finished committing transaction ${transactionState.id}"))

  private def persistState(
    id: FlowId,
    state0: PersistentExecutor.State[_, _],
    stateChange: PersistentExecutor.StateChange,
    recordingContext: RecordingRemoteContext
  ): ZIO[
    RemoteContext with VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog,
    IOException,
    PersistentExecutor.State[_, _]
  ] = {
    // TODO: optimization: do not persist state if there were no side effects
    val key    = id.toRaw
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
            RemoteContext.persistent(parentScope).flatMap { parentContext =>
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
      state2 = state1
                 .copy(
                   lastTimestamp = currentTimestamp,
                   watchPosition = Index(state1.watchPosition.max(lastIndex))
                 )
                 .recordAccessedVariables(readVariablesWithTimestamps)
                 .recordReadVariables(readVariables)
      persistedState = execEnv.serializer.serialize(state2)
      _             <- metrics.serializedFlowStateSize.update(persistedState.size)
      _             <- kvStore.put(Namespaces.workflowState, key, persistedState, currentTimestamp)
    } yield state2
  }

  private def loadState(id: FlowId): IO[IOException, Option[PersistentExecutor.State[_, _]]] =
    for {
      _              <- ZIO.logInfo(s"Looking for persisted flow state $id")
      key             = id.toRaw
      persistedState <- kvStore.getLatest(Namespaces.workflowState, key, None)
      state <- persistedState match {
                 case Some(bytes) =>
                   ZIO.logInfo(s"Using persisted state (${bytes.size} bytes)") *>
                     ZIO
                       .fromEither(execEnv.deserializer.deserialize[PersistentExecutor.State[Any, Any]](bytes))
                       .mapBoth(msg => new IOException(s"Failed to deserialize persisted state: $msg"), Some(_))
                 case None => ZIO.logInfo("No persisted state available").as(None)
               }
    } yield state

  private def deleteState(id: FlowId): IO[IOException, Unit] =
    for {
      _ <- ZIO.logInfo(s"Deleting persisted state $id")
      _ <- kvStore.delete(Namespaces.workflowState, id.toRaw)
    } yield ()

  private def interruptFlow(id: FlowId): ZIO[Any, Nothing, Boolean] =
    for {
      _     <- ZIO.log(s"Interrupting flow $id")
      state <- workflows.get(id).commit
      result <- state match {
                  case Some(runtimeState) =>
                    runtimeState.await.flatMap(_.fiber.interrupt.as(true))
                  case None =>
                    ZIO.succeed(false)
                }
    } yield result

  private def getAllReferences(name: ScopedRemoteVariableName): ZIO[Any, IOException, Set[ScopedRemoteVariableName]] =
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
  ): ZIO[Any, IOException, Set[ScopedRemoteVariableName]] = {
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
      promises    <- workflows.values.commit
      getStatuses <- ZIO.foreach(promises)(_.poll).map(_.flatten)
      statuses    <- ZIO.collectAll(getStatuses)
      byStatus     = statuses.groupBy(_.currentStatus)
      _ <- metrics
             .activeFlows(PersistentWorkflowStatus.Running)
             .set(byStatus.getOrElse(PersistentWorkflowStatus.Running, List.empty).length)
      _ <- metrics
             .activeFlows(PersistentWorkflowStatus.Suspended)
             .set(byStatus.getOrElse(PersistentWorkflowStatus.Suspended, List.empty).length)
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

  def make(
    operationExecutor: OperationExecutor[Any],
    serializer: Serializer,
    deserializer: Deserializer,
    gcPeriod: Duration = 5.minutes
  ): ZLayer[DurableLog with KeyValueStore, Nothing, ZFlowExecutor] =
    (ZLayer.succeed(
      ExecutionEnvironment(serializer, deserializer)
    )) >+> (DurableLog.any ++ KeyValueStore.any ++ RemoteVariableKeyValueStore.live) >>>
      ZLayer.scoped {
        for {
          durableLog            <- ZIO.service[DurableLog]
          kvStore               <- ZIO.service[KeyValueStore]
          remoteVariableKvStore <- ZIO.service[RemoteVariableKeyValueStore]
          execEnv               <- ZIO.service[ExecutionEnvironment]
          workflows             <- TMap.empty[FlowId, Promise[Nothing, PersistentExecutor.RuntimeState]].commit
          gcQueue               <- Queue.bounded[GarbageCollectionCommand](1)
          _ <- Promise
                 .make[Nothing, Any]
                 .flatMap(finished => gcQueue.offer(GarbageCollectionCommand(finished)))
                 .scheduleFork(Schedule.fixed(gcPeriod))
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
                UnboundRemoteFunction.make((error: Remote[ActivityError]) =>
                  ZFlow.fail(error).asInstanceOf[ZFlow[Any, Any, Any]]
                ),
                UnboundRemoteFunction.make((_: Remote[Unit]) => ZFlow.Fail(failure).asInstanceOf[ZFlow[Any, Any, Any]])
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
                UnboundRemoteFunction.make((error: Remote[ActivityError]) =>
                  ZFlow.fail(error).asInstanceOf[ZFlow[Any, Any, Any]]
                ),
                UnboundRemoteFunction.make((_: Remote[Unit]) => txState.body.asInstanceOf[ZFlow[Any, Any, Any]])
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
              status = if (suspend) PersistentWorkflowStatus.Suspended else PersistentWorkflowStatus.Running,
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
        state.copy(status = PersistentWorkflowStatus.Done)
    }
    private case object Resume extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(
          status = PersistentWorkflowStatus.Running,
          watchedVariables = Set.empty
        )
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
    val increaseForkCounter: StateChange                = IncreaseForkCounter
    val increaseTempVarCounter: StateChange             = IncreaseTempVarCounter
    def pushEnvironment(value: Remote[_]): StateChange  = PushEnvironment(value)
    def popEnvironment: StateChange                     = PopEnvironment
    def advanceClock(atLeastTo: Timestamp): StateChange = AdvanceClock(atLeastTo)
    val done: StateChange                               = Done
    val resume: StateChange                             = Resume
    def updateWatchPosition(index: Index): StateChange  = UpdateWatchPosition(index)
    def updateCurrentExecutionTime(now: OffsetDateTime, executionStartedAt: OffsetDateTime): StateChange =
      UpdateCurrentExecutionTime(now, executionStartedAt)
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
    result: DurablePromise[Either[Throwable, DynamicValue], FlowResult],
    envStack: List[Remote[_]],
    transactionStack: List[TransactionState],
    tempVarCounter: Int,
    promiseIdCounter: Int,
    forkCounter: Int,
    transactionCounter: Int,
    status: PersistentWorkflowStatus,
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

    def recordAccessedVariables(
      variables: Seq[(RemoteVariableName, Option[Timestamp], Boolean)]
    ): State[E, A] =
      transactionStack match {
        case currentTransaction :: rest =>
          copy(
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
          this
      }

    def recordReadVariables(variables: Set[ScopedRemoteVariableName]): State[E, A] =
      transactionStack match {
        case currentTransaction :: rest =>
          copy(
            transactionStack =
              currentTransaction.copy(readVariables = currentTransaction.readVariables union variables) :: rest
          )
        case Nil =>
          this
      }

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
        Schema.Field("id", Schema[ScopedFlowId]),
        Schema.Field("lastTimestamp", Schema[Timestamp]),
        Schema.Field("current", ZFlow.schemaAny),
        Schema.Field("stack", Schema[List[Instruction]]),
        Schema.Field("result", Schema[DurablePromise[Either[Throwable, DynamicValue], FlowResult]]),
        Schema.Field("envStack", Schema[List[Remote[_]]]),
        Schema.Field("transactionStack", Schema[List[TransactionState]]),
        Schema.Field("tempVarCounter", Schema[Int]),
        Schema.Field("promiseIdCounter", Schema[Int]),
        Schema.Field("forkCounter", Schema[Int]),
        Schema.Field("transactionCounter", Schema[Int]),
        Schema.Field("status", Schema[PersistentWorkflowStatus]),
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
          result: DurablePromise[Either[Throwable, DynamicValue], FlowResult],
          envStack: List[Remote[_]],
          transactionStack: List[TransactionState],
          tempVarCounter: Int,
          promiseIdCounter: Int,
          forkCounter: Int,
          transactionCounter: Int,
          status: PersistentWorkflowStatus,
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

  final case class RuntimeState(
    result: DurablePromise[Either[Throwable, DynamicValue], FlowResult],
    fiber: Fiber[Nothing, Unit],
    currentStatus: PersistentWorkflowStatus,
    executionStartedAt: OffsetDateTime
  )
}
