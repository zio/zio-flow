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
import zio.flow.Remote.{===>, RemoteFunction}
import zio.flow._
import zio.flow.serialization._
import zio.schema.Schema

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.annotation.nowarn

final case class PersistentExecutor(
  clock: Clock,
  execEnv: ExecutionEnvironment,
  durableLog: DurableLog,
  kvStore: KeyValueStore,
  opExec: OperationExecutor[Any],
  workflows: Ref[Map[String, Ref[PersistentExecutor.State[_, _]]]]
) extends ZFlowExecutor[String] {

  import PersistentExecutor._

  type Erased     = ZFlow[Any, Any, Any]
  type ErasedCont = Remote[Any] => ZFlow[Any, Any, Any]

  private val promiseEnv = ZEnvironment(durableLog, execEnv)

  def erase(flow: ZFlow[_, _, _]): Erased = flow.asInstanceOf[Erased]

  def eraseCont(cont: Remote[_] => ZFlow[_, _, _]): ErasedCont =
    cont.asInstanceOf[ErasedCont]

  def eval[A](r: Remote[A]): ZIO[RemoteContext, Nothing, SchemaAndValue[A]] =
    r.evalWithSchema.map(_.getOrElse(throw new IllegalStateException("Eval could not be reduced to Right of Either.")))

  def lit[A](a: A): Remote[A] =
    Remote.Literal(a, SchemaOrNothing.fromSchema(Schema.fail("It is not expected to serialize this value")))

  def coerceRemote[A](remote: Remote[_]): Remote[A] = remote.asInstanceOf[Remote[A]]

  //
  //    // 1. Read the environment `A` => ZFlow.Input (peek the environment)
  //    // 2. Push R onto the environment
  //    // 3. Run the workflow returned by `f`
  //
  //  state.update { state =>
  //    val cont = Instruction.handleSuccess(ZFlow.input[A])
  //    state.copy(stack = cont :: state.stack)
  //
  //  }

  def getVariable(workflowId: String, variableName: String): UIO[Option[Any]] =
    (for {
      map      <- workflows.get
      stateRef <- ZIO.fromOption(map.get(workflowId))
      state    <- stateRef.get
      value    <- ZIO.fromOption(state.getVariable(variableName))
    } yield value).unsome

  def setVariable(workflowId: String, variableName: String, value: SchemaAndValue[_]): UIO[Boolean] =
    (for {
      map      <- workflows.get
      stateRef <- ZIO.fromOption(map.get(workflowId))
      _        <- stateRef.update(state => state.copy(variables = state.variables.updated(variableName, value)))
      // TODO: It is time to retry a workflow that is suspended, because a variable changed.
      // _        <- stateRef.modify(state => (state.retry.forkDaemon, state.copy(retry = ZIO.unit))).flatten
    } yield true).catchAll(_ => UIO(false))

  def start[E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](
    uniqueId: String,
    flow: ZFlow[Any, E, A]
  ): UIO[DurablePromise[Either[Throwable, E], A]] = {
    import zio.flow.ZFlow._
    implicit val se: Schema[E] = SchemaOrNothing[E].schema
    implicit val sa: Schema[A] = SchemaOrNothing[A].schema

    def step(state: State[E, A]): ZIO[RemoteContext, IOException, StepResult] = {
      def onSuccess(value: Remote[_], stateChange: StateChange = StateChange.none): UIO[StepResult] =
        ZIO.succeed(StepResult(stateChange, Some(Right(value))))
      def onError(value: Remote[_], stateChange: StateChange = StateChange.none): UIO[StepResult] =
        ZIO.succeed(StepResult(stateChange, Some(Left(value))))

//      println(s"STEP ${state.current}")

      state.current match {
        case Return(value) =>
          onSuccess(value)

        case Now =>
          clock.instant.flatMap { currInstant =>
            onSuccess(coerceRemote(lit(currInstant)))
          }

        case Input() =>
          onSuccess(state.currentEnvironment.toRemote)

        case WaitTill(instant) =>
          val wait = for {
            start <- clock.instant
            end   <- eval(instant).map(_.value)
            _     <- clock.sleep(Duration.between(start, end))
          } yield ()
          wait *> onSuccess(())

        case Modify(svar, f0) =>
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

          val f = f0.asInstanceOf[Any ===> (A, Any)]
          for {
            value             <- RemoteContext.getVariable[Any](svar.identifier)
            tuple             <- eval(f(lit(value))).map(_.value)
            (result, newValue) = tuple
            _                 <- RemoteContext.setVariable[Any](svar.identifier, newValue)
//            _                 <- resume(vName, value, newValue)
            stepResult <- onSuccess(
                            result,
                            StateChange.addReadVar(svar.identifier)
                          ) // TODO: is it ok to add it only _after_ resume?
          } yield stepResult

        case apply @ Apply(_) =>
          val env = state.currentEnvironment
          ZIO.succeed(
            StepResult(StateChange.setCurrent(apply.lambda(env.toRemote)), None)
          )

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
          val a = for {
            inp    <- eval(input)
            output <- opExec.execute(inp.value, activity.operation)
          } yield output

          a.foldZIO(
            error => onError(lit(error)),
            success => onSuccess(lit(success), StateChange.addCompensation(activity.compensate.provide(lit(success))))
          )

        case tx @ Transaction(flow) =>
          val env = state.currentEnvironment.asInstanceOf[SchemaAndValue[tx.ValueR]].value
          ZIO.succeed(
            StepResult(
              StateChange.enterTransaction(flow.provide(lit(env))) ++
                StateChange.setCurrent(flow),
              None
            )
          )

        case ensuring @ Ensuring(flow, finalizer) =>
          val schemaE: SchemaOrNothing.Aux[ensuring.ValueE] =
            ensuring.errorSchema.asInstanceOf[SchemaOrNothing.Aux[ensuring.ValueE]]
          val schemaA: SchemaOrNothing.Aux[ensuring.ValueA] =
            ensuring.resultSchema.asInstanceOf[SchemaOrNothing.Aux[ensuring.ValueA]]
          val cont = Instruction.Continuation[Any, ensuring.ValueA, ensuring.ValueE, ensuring.ValueE, ensuring.ValueA](
            RemoteFunction { (e: Remote[ensuring.ValueE]) =>
              (finalizer *> ZFlow.fail(e)(schemaE).asInstanceOf[ZFlow[Any, Nothing, ensuring.ValueA]])(
                schemaE,
                SchemaOrNothing.fromSchema[Unit],
                schemaA
              )
            }(schemaE),
            RemoteFunction { (a: Remote[ensuring.ValueA]) =>
              (finalizer *> ZFlow.succeed(a))(schemaE, SchemaOrNothing[Unit], schemaA)
            }(schemaA)
          )

          ZIO.succeed(
            StepResult(StateChange.setCurrent(flow) ++ StateChange.pushContinuation(cont), None)
          )

        case Unwrap(remote) =>
          for {
            evaluatedFlow <- eval(remote)
          } yield StepResult(StateChange.setCurrent(evaluatedFlow.value), None)

        case UnwrapRemote(remote) =>
          for {
            evaluated <- eval(remote)
          } yield StepResult(StateChange.none, Some(Right(evaluated.value)))

        case fork @ Fork(workflow) =>
          val forkId = uniqueId + s"_fork_${state.forkCounter}"
          for {
            resultPromise <- start[fork.ValueE, fork.ValueA](
                               forkId,
                               workflow.asInstanceOf[ZFlow[Any, fork.ValueE, fork.ValueA]]
                             )(
                               fork.errorSchema.asInstanceOf[SchemaOrNothing.Aux[fork.ValueE]],
                               fork.resultSchema.asInstanceOf[SchemaOrNothing.Aux[fork.ValueA]]
                             )
          } yield StepResult(
            StateChange.increaseForkCounter,
            Some(Right(PersistentExecutingFlow(forkId, resultPromise)))
          )

        case await @ Await(execFlow) =>
          implicit val schemaEE: Schema[Either[Throwable, await.ValueE]] = await.schemaEitherE
          implicit val schemaE: SchemaOrNothing.Aux[await.ValueE] =
            await.errorSchema.asInstanceOf[SchemaOrNothing.Aux[await.ValueE]]
          implicit val schemaA: SchemaOrNothing.Aux[await.ValueA] =
            await.resultSchema.asInstanceOf[SchemaOrNothing.Aux[await.ValueA]]
          for {
            executingFlow <- eval(execFlow).map(_.value)
            _             <- ZIO.log("Waiting for result")
            result <-
              executingFlow
                .asInstanceOf[PersistentExecutingFlow[Either[Throwable, await.ValueE], await.ValueA]]
                .result
                .asInstanceOf[DurablePromise[Either[Throwable, await.ValueE], await.ValueA]]
                .awaitEither(SchemaOrNothing.fromSchema(schemaEE), schemaA.schema)
                .provideEnvironment(promiseEnv)
                .tapErrorCause(c => ZIO.log(s"Failed: $c"))
            _ <- ZIO.log(s"Await got result: $result")
            finishWith <-
              result.fold(
                error =>
                  error.fold(
                    die => ZIO.die(new IOException("Awaited flow died", die)),
                    error =>
                      ZIO.succeed(
                        Remote[Either[await.ValueE, await.ValueA]](Left(error))(
                          schemaEither(schemaE.schema, schemaA.schema)
                        )
                      )
                  ),
                success =>
                  ZIO.succeed(
                    Remote[Either[await.ValueE, await.ValueA]](Right(success))(
                      schemaEither(schemaE.schema, schemaA.schema)
                    )
                  )
              )
          } yield StepResult(StateChange.none, Some(Right(finishWith)))

        case timeout @ Timeout(flow, duration) =>
          for {
            d     <- eval(duration).map(_.value)
            forkId = uniqueId + s"_timeout_${state.forkCounter}"
            resultPromise <-
              start[timeout.ValueE, timeout.ValueA](
                forkId,
                flow.asInstanceOf[ZFlow[Any, timeout.ValueE, timeout.ValueA]]
              )(timeout.errorSchema, timeout.resultSchema.asInstanceOf[SchemaOrNothing.Aux[timeout.ValueA]])
            result <- resultPromise
                        .awaitEither(SchemaOrNothing.fromSchema(timeout.schemaEitherE), timeout.schemaA)
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
          eval(value).map { schemaAndValue =>
            StepResult(
              StateChange.setCurrent(flow) ++
                StateChange.pushContinuation(Instruction.PopEnv) ++
                StateChange.pushEnvironment(schemaAndValue),
              None
            )
          }

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

        case OrTry(left, right) =>
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

        case Interrupt(execFlow) =>
          val interrupt = for {
            exec <- eval(execFlow).map(_.asInstanceOf[Fiber[E, A]])
            exit <- exec.interrupt
          } yield exit.toEither

          interrupt.flatMap {
            case Left(error)    => onError(lit(error))
            case Right(success) => onSuccess(success)
          }

        case Fail(error) =>
          onError(error)

        case NewVar(name, initial) =>
          for {
            schemaAndValue <- eval(initial)
            vref            = Remote.Variable(RemoteVariableName(name), SchemaOrNothing.fromSchema(schemaAndValue.schema))
            _              <- RemoteContext.setVariable(RemoteVariableName(name), schemaAndValue.value)
          } yield StepResult(
            StateChange.addVariable(name, schemaAndValue),
            Some(Right(Remote[Remote[_]](vref)))
          )

        case i @ Iterate(initial, step0, predicate) =>
          implicit val schemaE: Schema[i.ValueE] = i.errorSchema.schema
          implicit val schemaA: Schema[i.ValueA] = i.resultSchema.schema

          val tempVarCounter = state.tempVarCounter
          val tempVarName    = s"_zflow_tempvar_${tempVarCounter}"

          def iterate(
            step: i.ValueA ===> ZFlow[Any, i.ValueE, i.ValueA],
            predicate: i.ValueA ===> Boolean,
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
//                result   <- iterate(step, predicate, stateVar, continue)// TODO: FIX SOE
              } yield a0, //result,
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

        case Log(message) =>
          ZIO.log(message) *> onSuccess(())

        case GetExecutionEnvironment =>
          onSuccess(lit(execEnv))
      }
    }

    def onSuccess(ref: Ref[State[E, A]], value: Remote[_]): ZIO[RemoteContext, IOException, Boolean] =
      ref.get.flatMap { state =>
//        println(s"==> SUCCESS[$value], stack:\n${state.stack.map("    " + _).mkString("\n")}")
        state.stack match {
          case Nil =>
            eval(value).flatMap { schemaAndValue =>
              state.result
                .succeed(schemaAndValue.value.asInstanceOf[A])
                .unit
                .provideEnvironment(promiseEnv)
            }.as(false)
          case Instruction.PopEnv :: newStack =>
            ref.update(state => state.copy(stack = newStack, envStack = state.envStack.tail)) *>
              onSuccess(ref, value)
          case Instruction.PushEnv(env) :: newStack =>
            eval(env).flatMap { schemaAndValue =>
              ref.update(state => state.copy(stack = newStack, envStack = schemaAndValue :: state.envStack))
            } *> onSuccess(ref, value)
          case Instruction.Continuation(_, onSuccess) :: newStack =>
            eval(onSuccess.apply(coerceRemote(value))).flatMap { next =>
              ref.update(_.copy(current = next.value, stack = newStack)).as(true)
            }
          case Instruction.PopFallback :: newStack =>
            ref.update(state =>
              state.copy(stack = newStack, tstate = state.tstate.popFallback.getOrElse(state.tstate))
            ) *> onSuccess(ref, value) //TODO : Fail in an elegant way
        }
      }

    def onError(ref: Ref[State[E, A]], value: Remote[_]): ZIO[RemoteContext, IOException, Boolean] =
      ref.get.flatMap { state =>
//        println(s"==> ERROR[$value], stack: ${state.stack}")
        state.stack match {
          case Nil =>
            eval(value).flatMap { schemaAndValue =>
              state.result
                .fail(Right(schemaAndValue.value.asInstanceOf[E]))
                .unit
                .provideEnvironment(promiseEnv)
            }.as(false)
          case Instruction.PopEnv :: newStack =>
            ref.update(state => state.copy(stack = newStack, envStack = state.envStack.tail)) *>
              onError(ref, value)
          case Instruction.PushEnv(env) :: newStack =>
            eval(env).flatMap { schemaAndValue =>
              ref.update(state => state.copy(stack = newStack, envStack = schemaAndValue :: state.envStack))
            } *> onError(ref, value)
          case Instruction.Continuation(onError, _) :: newStack =>
            eval(onError.apply(coerceRemote(value))).flatMap { next =>
              ref.update(_.copy(current = next.value, stack = newStack)).as(true)
            }
          case Instruction.PopFallback :: newStack =>
            ref.update(state =>
              state.copy(stack = newStack, tstate = state.tstate.popFallback.getOrElse(state.tstate))
            ) *> onError(ref, value) //TODO : Fail in an elegant way
        }
      }

    def runSteps(stateRef: Ref[State[E, A]]): ZIO[RemoteContext, IOException, Unit] =
      for {
        state0     <- stateRef.get
        stepResult <- step(state0)
        state1      = stepResult.stateChange(state0)
        _          <- stateRef.set(state1)
        _          <- persistState(uniqueId, state0, stepResult.stateChange, state1)
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

    val durablePromise =
      DurablePromise.make[Either[Throwable, E], A](uniqueId + "_result")
    val state =
      State(
        workflowId = uniqueId,
        current = flow,
        tstate = TState.Empty,
        stack = Nil,
        variables = Map(),
        result = durablePromise,
        envStack = Nil,
        tempVarCounter = 0,
        promiseIdCounter = 0,
        forkCounter = 0,
        retry = Nil,
        compileStatus = PersistentCompileStatus.Running
      )

    for {
      ref <- Ref.make[State[E, A]](state)
      _ <- runSteps(ref)
             .provide(RemoteContext.inMemory)
             .absorb
             .catchAll { error =>
               ZIO.logError(s"Persistent executor ${uniqueId} failed") *>
                 ZIO.logErrorCause(Cause.die(error)) *>
                 durablePromise
                   .fail(Left(error))
                   .provideEnvironment(promiseEnv)
                   .absorb
                   .catchAll { error2 =>
                     ZIO.logFatal(s"Failed to serialize execution failure: $error2")
                   }
                   .unit
             }
             .fork
      // TODO: store running workflow and it's fiber
    } yield state.result
  }

  def submit[E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](uniqueId: String, flow: ZFlow[Any, E, A]): IO[E, A] = {
    implicit val schemaE: Schema[E] = SchemaOrNothing[E].schema
    implicit val schemaA: Schema[A] = SchemaOrNothing[A].schema
    for {
      resultPromise <- start(uniqueId, flow)
      promiseResult <- resultPromise.awaitEither.provideEnvironment(promiseEnv).orDie
      _             <- ZIO.log(s"$uniqueId finished with $promiseResult")
      result <- promiseResult match {
                  case Left(Left(fail))   => ZIO.die(fail)
                  case Left(Right(error)) => ZIO.fail(error)
                  case Right(value)       => ZIO.succeed(value)
                }
    } yield result
  }

  @nowarn private def persistState(
    id: String,
    state0: PersistentExecutor.State[_, _],
    stateChange: PersistentExecutor.StateChange,
    state1: PersistentExecutor.State[_, _]
  ): IO[IOException, Unit] =
    // TODO
//    val key            = Chunk.fromArray(id.getBytes(StandardCharsets.UTF_8))
//    val persistedState = execEnv.serializer.serialize(state1)
//    kvStore.put("_zflow_workflow_states", key, persistedState).unit
    ZIO.unit
}

object PersistentExecutor {

  sealed trait Instruction

  object Instruction {

    case object PopEnv extends Instruction

    final case class PushEnv(env: Remote[_]) extends Instruction

    final case class Continuation[R, A, E, E2, B](
      onError: RemoteFunction[E, ZFlow[R, E2, B]],
      onSuccess: RemoteFunction[A, ZFlow[R, E2, B]]
    ) extends Instruction {

      override def toString: String =
        s"Continuation(\n  onError: $onError\n  onSuccess: $onSuccess\n)\n"
    }

    case object PopFallback extends Instruction
  }

  def make(
    opEx: OperationExecutor[Any],
    serializer: Serializer,
    deserializer: Deserializer
  ): ZLayer[Clock with DurableLog with KeyValueStore, Nothing, ZFlowExecutor[String]] =
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
          StateChange.SequentialChange(changes1.appended(otherChange))
        case (_, StateChange.SequentialChange(changes2)) =>
          StateChange.SequentialChange(changes2.prepended(self))
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
        state.copy(tstate = state.tstate.addCompensation(newCompensation.unit))
    }
    private final case class AddReadVar(name: RemoteVariableName) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(tstate = state.tstate.addReadVar(name))
    }
    private final case class EnterTransaction(flow: ZFlow[Any, _, _]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(tstate = state.tstate.enterTransaction(flow))
    }
    private case object IncreaseForkCounter extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(forkCounter = state.forkCounter + 1)
    }
    private case object IncreaseTempVarCounter extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(tempVarCounter = state.tempVarCounter + 1)
    }
    private final case class PushEnvironment(value: SchemaAndValue[_]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(envStack = value :: state.envStack)

    }
    private final case class AddVariable(name: String, value: SchemaAndValue[_]) extends StateChange {
      override def apply[E, A](state: State[E, A]): State[E, A] =
        state.copy(variables = state.variables + (name -> value))
    }

    val none: StateChange                                                             = NoChange
    def setCurrent(current: ZFlow[_, _, _]): StateChange                              = SetCurrent(current)
    def pushContinuation(cont: Instruction): StateChange                              = PushContinuation(cont)
    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): StateChange = AddCompensation(newCompensation)
    def addReadVar(name: RemoteVariableName): StateChange                             = AddReadVar(name)
    def enterTransaction(flow: ZFlow[Any, _, _]): StateChange                         = EnterTransaction(flow)
    val increaseForkCounter: StateChange                                              = IncreaseForkCounter
    val increaseTempVarCounter: StateChange                                           = IncreaseTempVarCounter
    def pushEnvironment(value: SchemaAndValue[_]): StateChange                        = PushEnvironment(value)
    def addVariable(name: String, value: SchemaAndValue[_]): StateChange              = AddVariable(name, value)
  }

  final case class State[E, A](
    workflowId: String,
    current: ZFlow[_, _, _],
    tstate: TState,
    stack: List[Instruction],
    variables: Map[String, SchemaAndValue[_]],
    result: DurablePromise[Either[Throwable, E], A],
    envStack: List[SchemaAndValue[_]],
    tempVarCounter: Int,
    promiseIdCounter: Int,
    forkCounter: Int,
    retry: List[FlowDurablePromise[_, _]],
    compileStatus: PersistentCompileStatus
  ) {

    def currentEnvironment: SchemaAndValue[_] = envStack.headOption.getOrElse(SchemaAndValue[Unit](Schema[Unit], ()))

    def getTransactionFlow: Option[ZFlow[Any, _, _]] = tstate match {
      case TState.Empty                      => None
      case TState.Transaction(flow, _, _, _) => Some(flow)
    }

    def addRetry(flowDurablePromise: FlowDurablePromise[_, _]): State[E, A] = copy(retry = flowDurablePromise :: retry)

    def setSuspended(): State[E, A] = copy(compileStatus = PersistentCompileStatus.Suspended)

    def getVariable(name: String): Option[SchemaAndValue[_]] = variables.get(name)

    //TODO scala map function
    // private lazy val lookupName: Map[Any, String] =
    //   variables.map((t: (String, _)) => t._2 -> t._1)
  }

  object State {
    implicit def schema[E, A]: Schema[State[E, A]] = Schema.fail("TODO: state serializer")
  }

}

sealed trait TState {
  self =>
  def addCompensation(newCompensation: ZFlow[Any, ActivityError, Unit]): TState = self match {
    case TState.Empty => TState.Empty
    case TState.Transaction(flow, readVars, compensation, fallBacks) =>
      TState.Transaction(flow, readVars, newCompensation *> compensation, fallBacks)
    //TODO : Compensation Failure semantics
  }

  def addReadVar(name: RemoteVariableName): TState = self match {
    case TState.Empty => TState.Empty
    case TState.Transaction(flow, readVars, compensation, fallbacks) =>
      TState.Transaction(flow, readVars + name, compensation, fallbacks)
  }

  def allVariables: Set[RemoteVariableName] = self match {
    case TState.Empty                          => Set()
    case TState.Transaction(_, readVars, _, _) => readVars
  }

  def enterTransaction(flow: ZFlow[Any, _, _]): TState =
    self match {
      case TState.Empty => TState.Transaction(flow, Set(), ZFlow.unit, Nil)
      case _            => self
    }

  def addFallback(zflow: ZFlow[Any, _, _]): Option[TState] =
    self match {
      case TState.Empty                                    => None
      case tstate @ TState.Transaction(_, _, _, fallbacks) => Some(tstate.copy(fallbacks = zflow :: fallbacks))
    }

  def popFallback: Option[TState] =
    self match {
      case TState.Empty                                    => None
      case tstate @ TState.Transaction(_, _, _, fallbacks) => Some(tstate.copy(fallbacks = fallbacks.drop(1)))
    }
}

object TState {

  case object Empty extends TState

  final case class Transaction(
    flow: ZFlow[Any, _, _],
    readVars: Set[RemoteVariableName],
    compensation: ZFlow[Any, ActivityError, Unit],
    fallbacks: List[ZFlow[Any, _, _]]
  ) extends TState

}

final case class FlowDurablePromise[E, A](flow: ZFlow[Any, E, A], promise: DurablePromise[E, A])

sealed trait PersistentCompileStatus

object PersistentCompileStatus {

  case object Running extends PersistentCompileStatus

  case object Done extends PersistentCompileStatus

  case object Suspended extends PersistentCompileStatus

}
