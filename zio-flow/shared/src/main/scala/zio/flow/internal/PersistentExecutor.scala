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
import zio.flow.serialization._
import zio.flow.{Remote, _}
import zio.schema.{CaseSet, DeriveSchema, DynamicValue, Schema}

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.annotation.nowarn

// TODO: better error type than IOException
final case class PersistentExecutor(
  clock: Clock,
  execEnv: ExecutionEnvironment,
  durableLog: DurableLog,
  kvStore: KeyValueStore,
  opExec: OperationExecutor[Any],
  workflows: Ref[Map[String, Ref[PersistentExecutor.State[_, _]]]]
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

  def start[E, A](
    id: FlowId,
    flow: ZFlow[Any, E, A]
  ): ZIO[Any, IOException, DurablePromise[Either[Throwable, DynamicValue], DynamicValue]] = {
    val durablePromise =
      DurablePromise.make[Either[Throwable, DynamicValue], DynamicValue](FlowId.unwrap(id + "_result"))

    loadState(id)
      .map(
        _.getOrElse(
          State(
            id = id,
            current = flow,
            stack = Nil,
            result = durablePromise,
            envStack = Nil,
            tempVarCounter = 0,
            promiseIdCounter = 0,
            forkCounter = 0,
            status = PersistentWorkflowStatus.Running
          )
        ).asInstanceOf[State[E, A]]
      )
      .flatMap { state =>
        run(state)
      }
  }

  def run[E, A](
    state: State[E, A]
  ): ZIO[Any, IOException, DurablePromise[Either[Throwable, DynamicValue], DynamicValue]] = {
    import zio.flow.ZFlow._

    def step(state: State[E, A]): ZIO[RemoteContext, IOException, StepResult] = {
      def onSuccess(value: Remote[_], stateChange: StateChange = StateChange.none): UIO[StepResult] =
        ZIO.succeed(StepResult(stateChange, Some(Right(value))))

      def onError(value: Remote[_], stateChange: StateChange = StateChange.none): UIO[StepResult] =
        ZIO.succeed(StepResult(stateChange, Some(Left(value))))

      ZIO.logDebug(s"STEP ${state.current.getClass.getSimpleName}") *> {
        state.current match {
          case Return(value) =>
            onSuccess(value)

          case Now =>
            clock.instant.flatMap { currInstant =>
              onSuccess(coerceRemote(Remote(currInstant)))
            }

          case Input() =>
            onSuccess(state.currentEnvironment)

          case WaitTill(instant) =>
            for {
              start   <- clock.instant
              end     <- eval(instant)(instantSchema)
              duration = Duration.between(start, end)
              _       <- ZIO.logInfo(s"Sleeping for $duration")
              _       <- clock.sleep(duration)
              _       <- ZIO.logInfo(s"Resuming execution after sleeping $duration")
              result  <- onSuccess(())
            } yield result

          case modify @ Modify(svar, f0) =>
            // TODO: resume
            //          def deserializeDurablePromise(bytes: Chunk[Byte]): DurablePromise[_, _] =
            //            ??? // TODO: implement deserialization of durable promise
            //
            //          def resume[A](variableName: String, oldValue: A, newValue: A): UIO[Unit] =
            //            if (oldValue == newValue)
            //              ZIO.unit
            //            else
            //              kvStore
            //                .scanAll(s"_zflow_suspended_workflows_readVar_$variableName")
            //                .foreach { case (_, value) =>
            //                  val durablePromise = deserializeDurablePromise(value)
            //                  durablePromise
            //                    .asInstanceOf[DurablePromise[Nothing, Unit]]
            //                    .succeed(())
            //                    .provideEnvironment(promiseEnv)
            //                }
            //                .orDie // TODO: handle errors looking up from key value store

            val f = f0.asInstanceOf[EvaluatedRemoteFunction[Any, (A, Any)]]
            for {
              _        <- ZIO.logDebug(s"Modify $svar")
              variable <- eval(svar)
              optValue <- RemoteContext.getVariable(variable.identifier)
              value <- ZIO
                         .fromOption(optValue)
                         .orElseFail(new IOException(s"Undefined variable ${variable.identifier} in Modify"))
              //            _                                      <- ZIO.debug(s"Modify: ${variable.identifier}'s previous value was $value")
              dynTuple <- evalDynamic(f(Remote.Literal(value, variable.schemaA)))
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
                              StateChange.addReadVar(variable.identifier)
                            ) // TODO: is it ok to add it only _after_ resume?
            } yield stepResult

          case apply @ Apply(_) =>
            val env         = state.currentEnvironment
            val appliedFlow = apply.lambda(env)
            for {
              nextFlow <- eval(appliedFlow)
            } yield StepResult(StateChange.setCurrent(nextFlow), None)

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
                None
              )
            )

          case RunActivity(input, activity) =>
            for {
              inp    <- eval(input)(activity.inputSchema)
              output <- opExec.execute(inp, activity.operation).either
              result <- output match {
                          case Left(error) => onError(Remote(error))
                          case Right(success) =>
                            val remoteSuccess = Remote(success)(activity.resultSchema.asInstanceOf[Schema[Any]])
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
                  StateChange.setCurrent(flow),
                None
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
              StepResult(StateChange.setCurrent(flow) ++ StateChange.pushContinuation(cont), None)
            )

          case Unwrap(remote) =>
            for {
              evaluatedFlow <- eval(remote)
            } yield StepResult(StateChange.setCurrent(evaluatedFlow), None)

          case UnwrapRemote(remote) =>
            for {
              evaluated <- eval(remote.asInstanceOf[Remote[Remote[Any]]])(Remote.schemaAny)
            } yield StepResult(StateChange.none, Some(Right(evaluated)))

          case fork @ Fork(workflow) =>
            val forkId = state.id + s"_fork_${state.forkCounter}"
            for {
              resultPromise <- start[fork.ValueE, fork.ValueA](
                                 forkId,
                                 workflow.asInstanceOf[ZFlow[Any, fork.ValueE, fork.ValueA]]
                               )
            } yield StepResult(
              StateChange.increaseForkCounter,
              Some(Right(Remote[ExecutingFlow[Any, Any]](PersistentExecutingFlow(forkId, resultPromise))))
            )

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
                  .asInstanceOf[DurablePromise[Either[Throwable, DynamicValue], DynamicValue]]
                  .awaitEither
                  .provideEnvironment(promiseEnv)
                  .tapErrorCause(c => ZIO.log(s"Failed: $c"))
              _ <- ZIO.log(s"Await got result: $result")
              finishWith <-
                result.fold(
                  error =>
                    error.fold(
                      die => ZIO.die(new IOException("Awaited flow died", die)),
                      dynamicError =>
                        ZIO.succeed(Remote.Either0(Left((Remote.Literal(dynamicError, schemaE), schemaA))))
                    ),
                  dynamicSuccess =>
                    ZIO.succeed(Remote.Either0(Right((schemaE, Remote.Literal(dynamicSuccess, schemaA)))))
                )
            } yield StepResult(StateChange.none, Some(Right(finishWith)))

          case timeout @ Timeout(flow, duration) =>
            for {
              d     <- eval(duration)
              forkId = state.id + s"_timeout_${state.forkCounter}"
              resultPromise <-
                start[timeout.ValueE, timeout.ValueA](
                  forkId,
                  flow.asInstanceOf[ZFlow[Any, timeout.ValueE, timeout.ValueA]]
                )
              result <- resultPromise.awaitEither
                          .provideEnvironment(promiseEnv)
                          .tapErrorCause(c => ZIO.log(s"Failed: $c"))
                          .timeout(d)
                          .provideEnvironment(ZEnvironment(clock))
              finishWith <- result match {
                              case Some(Right(success)) =>
                                // succeeded
                                ZIO.right(
                                  Remote[Option[timeout.ValueA]](Some(success))(
                                    Schema.option(timeout.schemaA)
                                  )
                                )
                              case Some(Left(Left(fatal))) =>
                                // failed with fatal error
                                ZIO.die(new IOException("Awaited flow died", fatal))
                              case Some(Left(Right(error))) =>
                                // failed with typed error
                                ZIO.left(Remote[timeout.ValueE](error)(timeout.schemaE))
                              case None =>
                                // timed out
                                // TODO: interrupt
                                ZIO.right(
                                  Remote[Option[timeout.ValueA]](None)(
                                    Schema.option(timeout.schemaA)
                                  )
                                )
                            }
            } yield StepResult(StateChange.increaseForkCounter, Some(finishWith))

          case Provide(value, flow) =>
            ZIO.succeed(
              StepResult(
                StateChange.setCurrent(flow) ++
                  StateChange.pushContinuation(Instruction.PopEnv) ++
                  StateChange.pushEnvironment(value),
                None
              )
            )

          case Die => ZIO.die(new IllegalStateException("Could not evaluate ZFlow"))

          case RetryUntil =>
            //          def storeSuspended(
            //            readVars: Set[String],
            //            durablePromise: DurablePromise[Nothing, Unit]
            //          ): IO[IOException, Unit] = {
            //            def namespace(readVar: String): String =
            //              s"_zflow_suspended_workflows_readVar_$readVar"
            //
            //            def key(durablePromise: DurablePromise[Nothing, Unit]): Chunk[Byte] =
            //              Chunk.fromArray(durablePromise.promiseId.getBytes)
            //
            //            def value(durablePromise: DurablePromise[Nothing, Unit]): Chunk[Byte] =
            //              ??? // TODO : Implement serialization of DurablePromise
            //
            //            ZIO.foreachDiscard(readVars)(readVar =>
            //              kvStore.put(namespace(readVar), key(durablePromise), value(durablePromise))
            //            )
            //          }

            //          ref.modify { state =>
            //            state.tstate match {
            //              case TState.Empty =>
            //                ZIO.unit -> state.copy(current = ZFlow.unit)
            //              case transaction @ TState.Transaction(_, _, _, fallback :: fallbacks) =>
            //                val tstate = transaction.copy(fallbacks = fallbacks)
            //                ZIO.unit -> state.copy(current = fallback, tstate = tstate)
            //              case TState.Transaction(_, readVars, _, Nil) =>
            //                val durablePromise = DurablePromise.make[Nothing, Unit](
            //                  s"_zflow_workflow_${state.workflowId}_durablepromise_${state.promiseIdCounter}"
            //                )
            //                storeSuspended(readVars, durablePromise) *>
            //                  durablePromise
            //                    .awaitEither(Schema.fail("nothing schema"), Schema[Unit])
            //                    .provideEnvironment(promiseEnv) ->
            //                  state.copy(
            //                    current = ZFlow.unit,
            //                    compileStatus = PersistentCompileStatus.Suspended,
            //                    promiseIdCounter = state.promiseIdCounter + 1
            //                  )
            //            }
            //          }.flatten *> step(ref)
            ??? // TODO

          case OrTry(_, _) =>
            //          for {
            //            state <- ref.get
            //            _ <- state.tstate.addFallback(erase(right).provide(state.currentEnvironment.toRemote)) match {
            //                   case None => ZIO.dieMessage("The OrTry operator can only be used inside transactions.")
            //                   case Some(tstate) =>
            //                     ref.set(
            //                       state.copy(current = left, tstate = tstate, stack = Instruction.PopFallback :: state.stack)
            //                     ) *> step(ref)
            //                 }
            //          } yield ()
            ??? // TODO

          case Interrupt(_) =>
            ??? // TODO
          //          val interrupt = for {
          //            exec <- eval(execFlow)
          //            exit <- exec.interrupt
          //          } yield exit.toEither
          //
          //          interrupt.flatMap {
          //            case Left(error)    => onError(lit(error))
          //            case Right(success) => onSuccess(success)
          //          }

          case Fail(error) =>
            onError(error)

          case NewVar(name, initial) =>
            for {
              schemaAndValue <- evalDynamic(initial)
              vref            = Remote.Variable(RemoteVariableName(name), schemaAndValue.schema)
              _              <- RemoteContext.setVariable(RemoteVariableName(name), schemaAndValue.value)
              _              <- ZIO.logDebug(s"Created new variable $name")
            } yield StepResult(
              StateChange.addVariable(name, schemaAndValue),
              Some(
                Right(
                  Remote.Literal(
                    SchemaAndValue.fromSchemaAndValue(
                      Remote.Variable.schema[Any],
                      vref.asInstanceOf[Remote.Variable[Any]]
                    )
                  )
                )
              )
            )

          case i @ Iterate(initial, step0, predicate) =>
            implicit val schemaE: Schema[i.ValueE] = i.errorSchema
            implicit val schemaA: Schema[i.ValueA] = i.resultSchema

            val tempVarCounter = state.tempVarCounter
            val tempVarName    = s"_zflow_tempvar_${tempVarCounter}"

            def iterate(
              step: Remote.EvaluatedRemoteFunction[i.ValueA, ZFlow[Any, i.ValueE, i.ValueA]],
              predicate: EvaluatedRemoteFunction[i.ValueA, Boolean],
              stateVar: Remote[Remote.Variable[i.ValueA]],
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

            ZIO.succeed(StepResult(StateChange.setCurrent(zFlow) ++ StateChange.increaseTempVarCounter, None))

          case Log(remoteMessage) =>
            eval(remoteMessage).flatMap { message =>
              ZIO.log(message) *> onSuccess(())
            }

          case GetExecutionEnvironment =>
            onSuccess(Remote.InMemoryLiteral(execEnv))
        }
      }
    }

    def onSuccess(ref: Ref[State[E, A]], value: Remote[_]): ZIO[RemoteContext, IOException, Boolean] =
      ref.get.flatMap { state =>
        //        println(s"==> SUCCESS[$value], stack:\n${state.stack.map("    " + _).mkString("\n")}")
        state.stack match {
          case Nil =>
            evalDynamic[A](coerceRemote(value)).flatMap { result =>
              state.result
                .succeed(result.value)
                .unit
                .provideEnvironment(promiseEnv)
            }.as(false)
          case Instruction.PopEnv :: newStack =>
            ref.update(state => state.copy(stack = newStack, envStack = state.envStack.tail)) *>
              onSuccess(ref, value)
          case Instruction.PushEnv(env) :: newStack =>
            ref.update(state => state.copy(stack = newStack, envStack = env :: state.envStack)) *> onSuccess(ref, value)
          case Instruction.Continuation(_, onSuccess) :: newStack =>
            eval(onSuccess.apply(coerceRemote(value))).flatMap { next =>
              ref.update(_.copy(current = next, stack = newStack)).as(true)
            }
          case Instruction.PopFallback :: newStack =>
            ref.update(state =>
              state.copy(
                stack = newStack
//                tstate = state.tstate.popFallback.getOrElse(state.tstate)
              )
            ) *> onSuccess(ref, value) //TODO : Fail in an elegant way
        }
      }

    def onError(ref: Ref[State[E, A]], value: Remote[_]): ZIO[RemoteContext, IOException, Boolean] =
      ref.get.flatMap { state =>
        //        println(s"==> ERROR[$value], stack: ${state.stack}")
        state.stack match {
          case Nil =>
            evalDynamic(value).flatMap { schemaAndValue =>
              state.result
                .fail(Right(schemaAndValue.value))
                .unit
                .provideEnvironment(promiseEnv)
            }.as(false)
          case Instruction.PopEnv :: newStack =>
            ref.update(state => state.copy(stack = newStack, envStack = state.envStack.tail)) *>
              onError(ref, value)
          case Instruction.PushEnv(env) :: newStack =>
            ref.update(state => state.copy(stack = newStack, envStack = env :: state.envStack)) *> onError(ref, value)
          case Instruction.Continuation(onError, _) :: newStack =>
            eval(onError.apply(coerceRemote(value))).flatMap { next =>
              ref.update(_.copy(current = next, stack = newStack)).as(true)
            }
          case Instruction.PopFallback :: newStack =>
            ref.update(state =>
              state.copy(stack =
                newStack
//                tstate = state.tstate.popFallback.getOrElse(state.tstate)
              )
            ) *> onError(ref, value) //TODO : Fail in an elegant way
        }
      }

    def runSteps(stateRef: Ref[State[E, A]]): ZIO[RemoteContext, IOException, Unit] =
      for {
        state0 <- stateRef.get

        recordingContext <- RecordingRemoteContext.startRecording
        stepResult       <- step(state0).provideService(recordingContext.remoteContext)
        state1            = stepResult.stateChange(state0)
        _                <- stateRef.set(state1)
        _                <- persistState(state.id, state0, stepResult.stateChange, state1, recordingContext)
        continue <- stepResult.result match {
                      case Some(Left(error)) =>
                        onError(stateRef, error)
                      case Some(Right(success)) =>
                        onSuccess(stateRef, success)
                      case None =>
                        ZIO.succeed(true)
                    }
        _ <- runSteps(stateRef).when(continue)
      } yield ()

    for {
      ref <- Ref.make[State[E, A]](state)
      _ <- runSteps(ref)
             .provide(RemoteContext.persistent, ZLayer.succeed(execEnv), ZLayer.succeed(kvStore))
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
             .zipLeft(deleteState(state.id).orDie)
             .fork
      // TODO: store running workflow and it's fiber
    } yield state.result
  }

  def submit[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): IO[E, A] =
    for {
      resultPromise <- start(id, flow).orDie
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
                      .fromEither(dynamicSuccess.toTypedValue(Schema[A]))
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
      // TODO: and store fibers
    } yield ()

  @nowarn private def persistState(
    id: FlowId,
    state0: PersistentExecutor.State[_, _],
    stateChange: PersistentExecutor.StateChange,
    state1: PersistentExecutor.State[_, _],
    recordingContext: RecordingRemoteContext
  ): IO[IOException, Unit] = {
    val key            = id.toRaw
    val persistedState = execEnv.serializer.serialize(state1)
    for {
      _                 <- ZIO.logInfo(s"Persisting flow state (${persistedState.size} bytes)")
      modifiedVariables <- recordingContext.getModifiedVariables
      _ <- ZIO
             .logInfo(s"Persisting changes to ${modifiedVariables.size} remote variables")
             .when(modifiedVariables.nonEmpty)
      _ <- kvStore.putAll(
             modifiedVariables.map { case (name, value) =>
               KeyValueStore.Item(
                 Namespaces.variables,
                 Chunk.fromArray(RemoteVariableName.unwrap(name).getBytes(StandardCharsets.UTF_8)),
                 execEnv.serializer.serialize(value)
               )
             } :+
               KeyValueStore.Item(Namespaces.workflowState, key, persistedState)
           )
    } yield ()
  }

  private def loadState(id: FlowId): IO[IOException, Option[PersistentExecutor.State[_, _]]] =
    for {
      _              <- ZIO.logInfo(s"Looking for persisted flow state $id")
      key             = id.toRaw
      persistedState <- kvStore.get(Namespaces.workflowState, key)
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

    case object PopFallback extends Instruction

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
            Schema.Case[PopFallback.type, Instruction](
              "PopFallback",
              Schema.singleton(PopFallback),
              _.asInstanceOf[PopFallback.type]
            )
          )
      )
  }

  def make(
    opEx: OperationExecutor[Any],
    serializer: Serializer,
    deserializer: Deserializer
  ): ZLayer[Clock with DurableLog with KeyValueStore, Nothing, ZFlowExecutor] =
    (
      for {
        durableLog <- ZIO.service[DurableLog]
        kvStore    <- ZIO.service[KeyValueStore]
        clock      <- ZIO.service[Clock]
        ref        <- Ref.make[Map[String, Ref[PersistentExecutor.State[_, _]]]](Map.empty)
        execEnv     = ExecutionEnvironment(serializer, deserializer)
      } yield PersistentExecutor(clock, execEnv, durableLog, kvStore, opEx, ref)
    ).toLayer

  case class StepResult(stateChange: StateChange, result: Option[Either[Remote[_], Remote[_]]])

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
    private final case class AddCompensation(newCompensation: ZFlow[Any, ActivityError, Any]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state // state.copy(tstate = state.tstate.addCompensation(newCompensation.unit))
    }
    private final case class AddReadVar(name: RemoteVariableName) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state // state.copy(tstate = state.tstate.addReadVar(name))
    }
    private final case class EnterTransaction(flow: ZFlow[Any, _, _]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state // state.copy(tstate = state.tstate.enterTransaction(flow))
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
    private final case class AddVariable(name: String, value: SchemaAndValue[_]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state // state.copy(variables = state.variables + (name -> value))
    }

    val none: StateChange                                                             = NoChange
    def setCurrent(current: ZFlow[_, _, _]): StateChange                              = SetCurrent(current)
    def pushContinuation(cont: Instruction): StateChange                              = PushContinuation(cont)
    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): StateChange = AddCompensation(newCompensation)
    def addReadVar(name: RemoteVariableName): StateChange                             = AddReadVar(name)
    def enterTransaction(flow: ZFlow[Any, _, _]): StateChange                         = EnterTransaction(flow)
    val increaseForkCounter: StateChange                                              = IncreaseForkCounter
    val increaseTempVarCounter: StateChange                                           = IncreaseTempVarCounter
    def pushEnvironment(value: Remote[_]): StateChange                                = PushEnvironment(value)
    def addVariable(name: String, value: SchemaAndValue[_]): StateChange              = AddVariable(name, value)
  }

  final case class State[E, A](
    id: FlowId,
    current: ZFlow[_, _, _],
    stack: List[Instruction],
    result: DurablePromise[Either[Throwable, DynamicValue], DynamicValue],
    envStack: List[Remote[_]],
    tempVarCounter: Int,
    promiseIdCounter: Int,
    forkCounter: Int,
    status: PersistentWorkflowStatus
  ) {

    def currentEnvironment: Remote[_] = envStack.headOption.getOrElse(
      Remote.unit
    )

    def setSuspended(): State[E, A] = copy(status = PersistentWorkflowStatus.Suspended)
  }

  object State {
    implicit def schema[E, A]: Schema[State[E, A]] =
      Schema.CaseClass9(
        Schema.Field("id", Schema[String]),
        Schema.Field("current", ZFlow.schemaAny),
        Schema.Field("stack", Schema[List[Instruction]]),
        Schema.Field("result", Schema[DurablePromise[Either[Throwable, DynamicValue], DynamicValue]]),
        Schema.Field("envStack", Schema[List[Remote[_]]]),
        Schema.Field("tempVarCounter", Schema[Int]),
        Schema.Field("promiseIdCounter", Schema[Int]),
        Schema.Field("forkCounter", Schema[Int]),
        Schema.Field("status", Schema[PersistentWorkflowStatus]),
        (
          id: String,
          current: ZFlow[_, _, _],
          stack: List[Instruction],
          result: DurablePromise[Either[Throwable, DynamicValue], DynamicValue],
          envStack: List[Remote[_]],
          tempVarCounter: Int,
          promiseIdCounter: Int,
          forkCounter: Int,
          status: PersistentWorkflowStatus
        ) =>
          State(
            FlowId(id),
            current,
            stack,
            result,
            envStack,
            tempVarCounter,
            promiseIdCounter,
            forkCounter,
            status
          ),
        state => FlowId.unwrap(state.id),
        _.current.asInstanceOf[ZFlow[Any, Any, Any]],
        _.stack,
        _.result,
        _.envStack,
        _.tempVarCounter,
        _.promiseIdCounter,
        _.forkCounter,
        _.status
      )
  }
}

final case class FlowDurablePromise[E, A](flow: ZFlow[Any, E, A], promise: DurablePromise[E, A])

sealed trait PersistentWorkflowStatus

object PersistentWorkflowStatus {
  case object Running   extends PersistentWorkflowStatus
  case object Done      extends PersistentWorkflowStatus
  case object Suspended extends PersistentWorkflowStatus

  implicit val schema: Schema[PersistentWorkflowStatus] = DeriveSchema.gen
}
