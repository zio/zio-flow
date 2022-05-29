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
import zio.flow.ExecutingFlow.PersistentExecutingFlow
import zio.flow.Remote.{EvaluatedRemoteFunction, RemoteFunction}
import zio.flow.internal.IndexedStore.Index
import zio.flow.serialization._
import zio.flow.{Remote, _}
import zio.schema.{CaseSet, DeriveSchema, DynamicValue, Schema}

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.annotation.nowarn

// TODO: better error type than IOException
final case class PersistentExecutor(
  execEnv: ExecutionEnvironment,
  durableLog: DurableLog,
  kvStore: KeyValueStore,
  opExec: OperationExecutor[Any],
  workflows: Ref[Map[FlowId, PersistentExecutor.RuntimeState[_, _]]]
) extends ZFlowExecutor {

  import PersistentExecutor._

  type Erased     = ZFlow[Any, Any, Any]
  type ErasedCont = Remote[Any] => ZFlow[Any, Any, Any]

  private val promiseEnv = ZEnvironment(durableLog, execEnv)

  private def coerceRemote[A](remote: Remote[_]): Remote[A] = remote.asInstanceOf[Remote[A]]

  private def eval[A: Schema](remote: Remote[A]): ZIO[RemoteContext, IOException, A] =
    remote.eval[A].mapError(msg => new IOException(s"Failed to evaluate remote: $msg"))

  private def evalDynamic[A](remote: Remote[A]): ZIO[RemoteContext, IOException, SchemaAndValue[A]] =
    remote.evalDynamic.mapError(msg => new IOException(s"Failed to evaluate remote: $msg"))

  def submit[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): IO[E, A] =
    for {
      resultPromise <- start(id, Nil, Timestamp(0L), Index(0L), flow).orDie
      promiseResult <- resultPromise.awaitEither.provideEnvironment(promiseEnv).orDie
      _             <- ZIO.log(s"$id finished with $promiseResult")
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
                                val id = FlowId(new String(rawKey.toArray, StandardCharsets.UTF_8))
                                ZIO
                                  .fromEither(
                                    execEnv.deserializer.deserialize[PersistentExecutor.State[Any, Any]](rawState)
                                  )
                                  .mapBoth(
                                    error => new IOException(s"Failed to deserialize state of $id: $error"),
                                    state => (FlowId(new String(rawKey.toArray, StandardCharsets.UTF_8)), state)
                                  )
                              }
                              .runCollect
      _ <- ZIO.foreachDiscard(deserializedStates) { case (id, state) =>
             ZIO.log(s"Restarting $id") *>
               run(state).orDie
           }
    } yield ()

  private def start[E, A](
    id: FlowId,
    parentStack: List[FlowId],
    lastTimestamp: Timestamp,
    watchPosition: Index,
    flow: ZFlow[Any, E, A]
  ): ZIO[Any, IOException, DurablePromise[Either[Throwable, DynamicValue], FlowResult]] =
    workflows.get.flatMap { runningWorkflows =>
      runningWorkflows.get(id) match {
        case Some(runtimeState) =>
          ZIO.logInfo(s"Flow $id is already running").as(runtimeState.result)

        case None =>
          val durablePromise =
            DurablePromise.make[Either[Throwable, DynamicValue], FlowResult](FlowId.unwrap(id + "_result"))

          loadState(id)
            .map(
              _.getOrElse(
                State(
                  id = id,
                  parentStack = parentStack,
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
                  watchPosition = watchPosition
                )
              ).asInstanceOf[State[E, A]]
            )
            .flatMap { state =>
              run(state)
            }
      }
    }

  private def run[E, A](
    state: State[E, A]
  ): ZIO[Any, IOException, DurablePromise[Either[Throwable, DynamicValue], FlowResult]] = {
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
        ZIO.logDebug(s"onSuccess in scope ${scope} with value ${value}") *>
          remoteContext {
            updatedState.stack match {
              case Nil =>
                evalDynamic(value).flatMap { result =>
                  state.result
                    .succeed(FlowResult(result.value, updatedState.lastTimestamp))
                    .unit
                    .provideEnvironment(promiseEnv)
                }.as(
                  StepResult(
                    stateChange ++ StateChange.done,
                    continue = false
                  )
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
                      evalDynamic(value).flatMap { evaluatedValue =>
                        onSuccess(
                          Remote.Literal(evaluatedValue),
                          stateChange ++ StateChange.popContinuation ++ StateChange.leaveTransaction
                        )
                      }
                    } else {
                      for {
                        _ <- ZIO.logInfo("Commit failed, reverting and retrying")
                        result = StepResult(
                                   stateChange ++
                                     StateChange.popContinuation ++
                                     StateChange.pushContinuation(Instruction.CommitTransaction) ++
                                     StateChange.restartCurrentTransaction(suspend = false),
                                   continue = true
                                 )
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
              evalDynamic(value).flatMap { schemaAndValue =>
                state.result
                  .fail(Right(schemaAndValue.value))
                  .unit
                  .provideEnvironment(promiseEnv)
              }.as(
                StepResult(
                  stateChange ++ StateChange.done,
                  continue = false
                )
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
            case Instruction.CommitTransaction :: _ =>
              evalDynamic(value).map { schemaAndValue =>
                // Inside a transaction this is always a TransactionFailure which we have to unwrap here
                TransactionFailure.unwrapDynamic(schemaAndValue) match {
                  case Some(failure) =>
                    StepResult(
                      stateChange ++ StateChange.popContinuation ++ StateChange.revertCurrentTransaction(
                        Remote.Literal(failure)
                      ) ++ StateChange.leaveTransaction,
                      continue = true
                    )
                  case None =>
                    StepResult(
                      stateChange ++
                        StateChange.popContinuation ++
                        StateChange.pushContinuation(Instruction.CommitTransaction) ++
                        StateChange.restartCurrentTransaction(suspend = true),
                      continue = true
                    )
                }
              }
          }
        }
      }

      def failWith(error: SchemaAndValue[_], stateChange: StateChange = StateChange.none) =
        onError(
          if (state.isInTransaction)
            Remote.Literal(TransactionFailure.wrapDynamic(error))
          else
            Remote.Literal(error),
          stateChange
        )

      Console.printLine(
        scala.Console.GREEN + s"============ [${state.scope.toString}] STEP ${state.current.getClass.getSimpleName} ============ " + scala.Console.RESET
      ) *> {
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

          case Read(svar, schema) =>
            for {
              variableReference <- eval(svar)
              variable           = Remote.Variable(variableReference.name, schema)
              stepResult        <- onSuccess(variable, StateChange.none)
            } yield stepResult

          case modify @ Modify(svar, f0) =>
            val f = f0.asInstanceOf[EvaluatedRemoteFunction[Any, (A, Any)]]
            for {
              variableReference <- eval(svar)
              variable           = Remote.Variable(variableReference.name, f.input.schema)
              //            _                                      <- ZIO.debug(s"Modify: ${variable.identifier}'s previous value was $value")
              dynTuple <- evalDynamic(f(variable))
              tuple <- dynTuple.value match {
                         case DynamicValue.Tuple(dynResult, newValue) => ZIO.succeed((dynResult, newValue))
                         case _                                       => ZIO.fail(new IOException(s"Modify's result was not a tuple"))
                       }
              //            _                                      <- ZIO.debug(s"Modify: result is $tuple")
              (dynResult, newValue) = tuple
              _                    <- RemoteContext.setVariable(variable.identifier, newValue)
              //            _                                      <- ZIO.debug(s"Modify: changed value of ${variable.identifier} to $newValue")
              result = Remote.Literal(dynResult, modify.resultSchema)
              //            _                 <- resume(vName, value, newValue)
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
              output <- opExec.execute(inp, activity.operation).either
              result <- output match {
                          case Left(error) => failWith(SchemaAndValue.of(error))
                          case Right(success) =>
                            val remoteSuccess = Remote(success)(activity.resultSchema.asInstanceOf[Schema[Any]])
                            // TODO: take advantage of activity.check
                            onSuccess(
                              remoteSuccess,
                              StateChange.addCompensation(activity.compensate.provide(remoteSuccess))
                            )
                        }
            } yield result

          case tx @ Transaction(flow) =>
            val env = state.currentEnvironment
            ZIO.succeed(
              StepResult(
                StateChange.enterTransaction(flow.provide(env.asInstanceOf[Remote[tx.ValueR]])) ++
                  StateChange.pushContinuation(Instruction.CommitTransaction) ++
                  StateChange.setCurrent(flow),
                continue = true
              )
            )

          case ensuring @ Ensuring(flow, finalizer) =>
            implicit val schemaE: Schema[ensuring.ValueE] =
              ensuring.errorSchema.asInstanceOf[Schema[ensuring.ValueE]]
            val schemaA: Schema[ensuring.ValueA] =
              ensuring.resultSchema.asInstanceOf[Schema[ensuring.ValueA]]
            val cont =
              Instruction.Continuation[Any, ensuring.ValueA, ensuring.ValueE, ensuring.ValueE, ensuring.ValueA](
                RemoteFunction { (e: Remote[ensuring.ValueE]) =>
                  (finalizer *> ZFlow.fail(e).asInstanceOf[ZFlow[Any, ensuring.ValueE, ensuring.ValueA]])(
                    schemaE,
                    Schema[Unit],
                    schemaA
                  )
                }(schemaE).evaluated,
                RemoteFunction { (a: Remote[ensuring.ValueA]) =>
                  (finalizer *> ZFlow.succeed(a).asInstanceOf[ZFlow[Any, ensuring.ValueE, ensuring.ValueA]])(
                    schemaE,
                    Schema[Unit],
                    schemaA
                  )
                }(schemaA).evaluated
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
              evaluated <- eval(remote.asInstanceOf[Remote[Remote[Any]]])(Remote.schemaAny)
              result    <- onSuccess(evaluated)
            } yield result

          case fork @ Fork(workflow) =>
            val forkId = state.id + s"_fork_${state.forkCounter}"
            for {
              resultPromise <- start[fork.ValueE, fork.ValueA](
                                 forkId,
                                 state.id :: state.parentStack,
                                 state.lastTimestamp.next,
                                 state.watchPosition,
                                 workflow.asInstanceOf[ZFlow[Any, fork.ValueE, fork.ValueA]]
                               )
              result <- onSuccess(
                          Remote[ExecutingFlow[Any, Any]](PersistentExecutingFlow(forkId, resultPromise)),
                          StateChange.increaseForkCounter
                        )
            } yield result

          case await @ Await(execFlow) =>
            implicit val schemaE: Schema[await.ValueE] = await.schemaE
            implicit val schemaA: Schema[await.ValueA] = await.schemaA
            for {
              executingFlow <- eval(execFlow)
              _             <- ZIO.log("Waiting for result")
              result <-
                executingFlow
                  .asInstanceOf[PersistentExecutingFlow[Either[Throwable, await.ValueE], await.ValueA]]
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
                        dynamicError =>
                          ZIO.succeed(Remote.Either0(Left((Remote.Literal(dynamicError, schemaE), schemaA))))
                      )
                      .flatMap { finishWith =>
                        onSuccess(finishWith)
                      },
                  dynamicSuccess =>
                    onSuccess(
                      Remote.Either0(Right((schemaE, Remote.Literal(dynamicSuccess.result, schemaA)))),
                      StateChange.advanceClock(dynamicSuccess.timestamp)
                    )
                )
            } yield stepResult

          case timeout @ Timeout(flow, duration) =>
            for {
              d     <- eval(duration)
              forkId = state.id + s"_timeout_${state.forkCounter}"
              resultPromise <-
                start[timeout.ValueE, timeout.ValueA](
                  forkId,
                  state.id :: state.parentStack,
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
                                  Remote.Literal(DynamicValue.SomeValue(dynamicSuccess.result), timeout.resultSchema),
                                  StateChange.increaseForkCounter ++ StateChange.advanceClock(dynamicSuccess.timestamp)
                                )
                              case Some(Left(Left(fatal))) =>
                                // failed with fatal error
                                ZIO.die(new IOException("Awaited flow died", fatal))
                              case Some(Left(Right(dynamicError))) =>
                                // failed with typed error
                                failWith(
                                  SchemaAndValue(timeout.errorSchema, dynamicError),
                                  StateChange.increaseForkCounter
                                )
                              case None =>
                                // timed out
                                interruptFlow(forkId).zipRight(
                                  onSuccess(
                                    Remote.Literal(DynamicValue.NoneValue, timeout.resultSchema),
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

          case OrTry(_, _) =>
            ??? // TODO

          case Interrupt(remoteExecFlow) =>
            for {
              executingFlow          <- eval(remoteExecFlow)
              persistentExecutingFlow = executingFlow.asInstanceOf[PersistentExecutingFlow[Any, Any]]
              interrupted            <- interruptFlow(persistentExecutingFlow.id)
              result <- if (interrupted)
                          onSuccess(Remote.unit)
                        else
                          failWith(
                            SchemaAndValue.of(
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
              schemaAndValue    <- evalDynamic(initial)
              remoteVariableName = RemoteVariableName(name)
              vref               = RemoteVariableReference[Any](remoteVariableName)
              _                 <- RemoteContext.setVariable(remoteVariableName, schemaAndValue.value)
              _                 <- ZIO.logDebug(s"Created new variable $name")
              result            <- onSuccess(Remote(vref), StateChange.none)
            } yield result

          case i @ Iterate(initial, step0, predicate) =>
            implicit val schemaE: Schema[i.ValueE] = i.errorSchema
            implicit val schemaA: Schema[i.ValueA] = i.resultSchema

            val tempVarCounter = state.tempVarCounter
            val tempVarName    = s"_zflow_tempvar_${tempVarCounter}"

            def iterate(
              step: Remote.EvaluatedRemoteFunction[i.ValueA, ZFlow[Any, i.ValueE, i.ValueA]],
              predicate: EvaluatedRemoteFunction[i.ValueA, Boolean],
              stateVar: Remote[RemoteVariableReference[i.ValueA]],
              boolRemote: Remote[Boolean]
            ): ZFlow[Any, i.ValueE, i.ValueA] =
              ZFlow.ifThenElse(boolRemote)(
                for {
                  a0       <- stateVar.get(schemaA)
                  nextFlow <- step(a0)
                  a1       <- ZFlow.unwrap(nextFlow)(i.errorSchema, i.resultSchema)
                  _        <- stateVar.set(a1)(schemaA)
                  continue <- predicate(a1)
                  result   <- iterate(step, predicate, stateVar, continue)
                } yield result,
                stateVar.get
              )

            val zFlow = for {
              stateVar   <- ZFlow.newVar[i.ValueA](tempVarName, initial)
              stateValue <- stateVar.get
              boolRemote <- ZFlow(predicate(stateValue))
              _          <- ZFlow.log(s"stateValue = $stateValue")
              _          <- ZFlow.log(s"boolRemote = $boolRemote")
              _          <- ZFlow.log(s"boolRemote = $boolRemote")
              stateValue <- iterate(step0, predicate, stateVar, boolRemote)
            } yield stateValue

            ZIO.succeed(
              StepResult(StateChange.setCurrent(zFlow) ++ StateChange.increaseTempVarCounter, continue = true)
            )

          case Log(remoteMessage) =>
            eval(remoteMessage).flatMap { message =>
              ZIO.log(message) *> onSuccess(())
            }

          case GetExecutionEnvironment =>
            onSuccess(Remote.InMemoryLiteral(execEnv))
        }
      }
    }

    // TODO: move somewhere better
    def optionalAnnotate[R, E, A](key: => String, value: => Option[String])(f: ZIO[R, E, A]): ZIO[R, E, A] =
      value match {
        case Some(value) => ZIO.logAnnotate(key, value)(f)
        case None        => f
      }

    def waitForVariablesToChange(
      watchedVariables: Set[ScopedRemoteVariableName],
      watchPosition: Index
    ): ZIO[Any, IOException, Unit] =
      durableLog
        .subscribe(
          Topics.variableChanges(state.scope.rootScope.flowId),
          watchPosition
        )
        .map { raw =>
          execEnv.deserializer.deserialize[ScopedRemoteVariableName](raw)
        }
        .filter {
          case Right(scopedName) => watchedVariables.contains(scopedName)
          case Left(_)           => false
        }
        .runHead
        .unit

    def runSteps(
      stateRef: Ref[State[E, A]]
    ): ZIO[
      VirtualClock with KeyValueStore with ExecutionEnvironment with DurableLog with RemoteVariableKeyValueStore,
      IOException,
      Unit
    ] =
      stateRef.get.flatMap { state0 =>
        ZIO.logAnnotate("flowId", FlowId.unwrap(state0.id)) {
          optionalAnnotate("txId", state0.transactionStack.headOption.map(s => TransactionId.unwrap(s.id))) {
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
                      waitForVariablesToChange(state0.watchedVariables, state0.watchPosition.next)
                        .as(StepResult(StateChange.resume, continue = true))
                  }
                state1  = stepResult.stateChange(state0)
                state2 <- persistState(state.id, state0, stepResult.stateChange, state1, recordingContext)
                _      <- stateRef.set(state2.asInstanceOf[PersistentExecutor.State[E, A]])
              } yield stepResult
            }
          }.flatMap { stepResult =>
            runSteps(stateRef).when(stepResult.continue).unit
          }
        }
      }

    for {
      ref       <- Ref.make[State[E, A]](state)
      startGate <- Promise.make[Nothing, Unit]
      fiber <- (for {
                 _ <- startGate.await
                 _ <- runSteps(ref)
                        .provide(
                          ZLayer.succeed(execEnv),
                          ZLayer.succeed(kvStore),
                          ZLayer.succeed(durableLog),
                          ZLayer(VirtualClock.make(state.lastTimestamp)),
                          RemoteVariableKeyValueStore.live
                        )
                        .absorb
                        .catchAll { error =>
                          ZIO.logError(s"Persistent executor ${state.id} failed") *>
                            ZIO.logErrorCause(Cause.die(error)) *>
                            state.result
                              .fail(Left(error))
                              .provideEnvironment(promiseEnv)
                              .absorb
                              .catchAll { error2 =>
                                ZIO.logFatal(s"Failed to serialize execution failure: $error2")
                              }
                              .unit
                        }
                 _ <- deleteState(state.id).orDie
               } yield ()).ensuring(workflows.update(_ - state.id)).fork
      runtimeState = PersistentExecutor.RuntimeState(state.result, fiber)
      _           <- workflows.update(_ + (state.id -> runtimeState))
      _           <- startGate.succeed(())
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
                  s"Variable ${name} in ${transactionState.id} latest: $latestTimestamp previous: ${access.previousTimestamp}"
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

  @nowarn private def persistState(
    id: FlowId,
    state0: PersistentExecutor.State[_, _],
    stateChange: PersistentExecutor.StateChange,
    state1: PersistentExecutor.State[_, _],
    recordingContext: RecordingRemoteContext
  ): ZIO[
    RemoteContext with VirtualClock with KeyValueStore with RemoteVariableKeyValueStore with ExecutionEnvironment with DurableLog,
    IOException,
    PersistentExecutor.State[_, _]
  ] = {
    // TODO: optimization: do not persist state if there were no side effects
    val key = id.toRaw
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
            s"Persisting changes to ${modifiedVariables.size} remote variables\n(${modifiedVariables.mkString("\n")})"
          )
          .when(modifiedVariables.nonEmpty)

      remoteContext = recordingContext.commitContext
      _ <- ZIO.foreachDiscard(modifiedVariables) { case (name, value) =>
             remoteContext.setVariable(name, value)
           }
      lastIndex <- RemoteVariableKeyValueStore.getLastIndex
      _         <- ZIO.logDebug(s"Recording accessed variables: ${readVariablesWithTimestamps.mkString("\n")}")
      state2 = state1
                 .copy(
                   lastTimestamp = currentTimestamp,
                   watchPosition = Index(state1.watchPosition.max(lastIndex))
                 )
                 .recordAccessedVariables(readVariablesWithTimestamps)
                 .recordReadVariables(readVariables)
      persistedState = execEnv.serializer.serialize(state2)
      _             <- ZIO.logInfo(s"Persisting flow state (${persistedState.size} bytes)")
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
      // TODO: delete persisted variables
    } yield ()

  private def interruptFlow(id: FlowId): ZIO[Any, Nothing, Boolean] =
    for {
      _           <- ZIO.log(s"Interrupting flow $id")
      workflowMap <- workflows.get
      result <- workflowMap.get(id) match {
                  case Some(runtimeState) =>
                    runtimeState.fiber.interrupt.as(true)
                  case None =>
                    ZIO.succeed(false)
                }
    } yield result
}

object PersistentExecutor {

  sealed trait Instruction

  object Instruction {
    case object PopEnv extends Instruction

    final case class PushEnv(env: Remote[_]) extends Instruction

    final case class Continuation[R, A, E, E2, B](
      onError: EvaluatedRemoteFunction[E, ZFlow[R, E2, B]],
      onSuccess: EvaluatedRemoteFunction[A, ZFlow[R, E2, B]]
    ) extends Instruction {

      override def toString: String =
        s"Continuation(\n  onError: $onError\n  onSuccess: $onSuccess\n)\n"
    }

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
              Schema.CaseClass2[EvaluatedRemoteFunction[Any, ZFlow[Any, Any, Any]], EvaluatedRemoteFunction[
                Any,
                ZFlow[Any, Any, Any]
              ], Continuation[Any, Any, Any, Any, Any]](
                Schema.Field("onError", EvaluatedRemoteFunction.schema[Any, ZFlow[Any, Any, Any]]),
                Schema.Field("onSuccess", EvaluatedRemoteFunction.schema[Any, ZFlow[Any, Any, Any]]),
                Continuation(_, _),
                _.onError,
                _.onSuccess
              ),
              _.asInstanceOf[Continuation[Any, Any, Any, Any, Any]]
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
    opEx: OperationExecutor[Any],
    serializer: Serializer,
    deserializer: Deserializer
  ): ZLayer[DurableLog with KeyValueStore, Nothing, ZFlowExecutor] =
    ZLayer {
      for {
        durableLog <- ZIO.service[DurableLog]
        kvStore    <- ZIO.service[KeyValueStore]
        ref        <- Ref.make[Map[FlowId, PersistentExecutor.RuntimeState[_, _]]](Map.empty)
        execEnv     = ExecutionEnvironment(serializer, deserializer)
      } yield PersistentExecutor(execEnv, durableLog, kvStore, opEx, ref)
    }

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
        val transactionId = TransactionId("tx" + state.transactionCounter.toString)
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
                RemoteFunction((error: Remote[ActivityError]) =>
                  ZFlow.fail(error).asInstanceOf[ZFlow[Any, Any, Any]]
                ).evaluated,
                RemoteFunction((_: Remote[Unit]) => ZFlow.Fail(failure).asInstanceOf[ZFlow[Any, Any, Any]]).evaluated
              )(txState.body.errorSchema.asInstanceOf[Schema[Any]], txState.body.resultSchema.asInstanceOf[Schema[Any]])
            state.copy(
              current = compensateAndFail
            )
          case None => state
        }
    }
    private final case class RestartCurrentTransaction(suspend: Boolean) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.transactionStack.headOption match {
          case Some(txState) =>
            val compensations = txState.compensations.foldLeft[ZFlow[Any, ActivityError, Unit]](ZFlow.unit)(_ *> _)
            val compensateAndRun: ZFlow[_, _, _] =
              ZFlow.Fold(
                compensations,
                RemoteFunction((error: Remote[ActivityError]) =>
                  ZFlow.fail(error).asInstanceOf[ZFlow[Any, Any, Any]]
                ).evaluated,
                RemoteFunction((_: Remote[Unit]) => txState.body.asInstanceOf[ZFlow[Any, Any, Any]]).evaluated
              )(txState.body.errorSchema.asInstanceOf[Schema[Any]], txState.body.resultSchema.asInstanceOf[Schema[Any]])
            state.copy(
              current = compensateAndRun,
              transactionStack =
                txState.copy(accessedVariables = Map.empty, readVariables = Set.empty) :: state.transactionStack.tail,
              status = if (suspend) PersistentWorkflowStatus.Suspended else PersistentWorkflowStatus.Running,
              watchedVariables = txState.readVariables
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
    def restartCurrentTransaction(suspend: Boolean): StateChange     = RestartCurrentTransaction(suspend)
    val increaseForkCounter: StateChange                             = IncreaseForkCounter
    val increaseTempVarCounter: StateChange                          = IncreaseTempVarCounter
    def pushEnvironment(value: Remote[_]): StateChange               = PushEnvironment(value)
    def popEnvironment: StateChange                                  = PopEnvironment
    def advanceClock(atLeastTo: Timestamp): StateChange              = AdvanceClock(atLeastTo)
    val done: StateChange                                            = Done
    val resume: StateChange                                          = Resume
    def updateWatchPosition(index: Index): StateChange               = UpdateWatchPosition(index)
  }

  final case class FlowResult(result: DynamicValue, timestamp: Timestamp)
  object FlowResult {
    implicit val schema: Schema[FlowResult] = DeriveSchema.gen
  }

  final case class State[E, A](
    id: FlowId,
    parentStack: List[FlowId],
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
    watchPosition: Index
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

    private def parentStackToScope(parentStack: ::[FlowId]): RemoteVariableScope =
      parentStack match {
        case ::(head, Nil)              => RemoteVariableScope.TopLevel(head)
        case ::(head, rest: ::[FlowId]) => RemoteVariableScope.Fiber(head, parentStackToScope(rest))
      }

    def scope: RemoteVariableScope =
      transactionStack match {
        case ::(head, next) =>
          RemoteVariableScope.Transactional(
            this.copy(transactionStack = next).scope,
            head.id
          )
        case Nil =>
          parentStack match {
            case Nil => RemoteVariableScope.TopLevel(id)
            case parents @ ::(head, next) =>
              RemoteVariableScope.Fiber(
                id,
                parentStackToScope(parents)
              )
          }
      }
  }

  object State {
    implicit def schema[E, A]: Schema[State[E, A]] =
      Schema.CaseClass15(
        Schema.Field("id", Schema[String]),
        Schema.Field("parentStack", Schema[List[String]]),
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
        (
          id: String,
          parentStack: List[String],
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
          watchPosition: Index
        ) =>
          State(
            FlowId(id),
            parentStack.map(FlowId(_)),
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
            watchPosition
          ),
        state => FlowId.unwrap(state.id),
        state => FlowId.unwrapAll(state.parentStack),
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
        _.watchPosition
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

  final case class RuntimeState[E, A](
    result: DurablePromise[Either[Throwable, DynamicValue], FlowResult],
    fiber: Fiber[Nothing, Unit]
  )
}
