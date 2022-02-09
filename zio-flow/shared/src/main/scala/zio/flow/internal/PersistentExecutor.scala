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

import java.io.IOException
import java.time.Duration
import zio._
import zio.flow._
import zio.flow.serialization._
import zio.schema.Schema

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

  def eval[A](r: Remote[A]): UIO[SchemaAndValue[A]] = UIO(
    r.evalWithSchema.getOrElse(throw new IllegalStateException("Eval could not be reduced to Right of Either."))
  )

  def lit[A](a: A): Remote[A] =
    Remote.Literal(a, Schema.fail("It is not expected to serialize this value"))

  def coerceRemote[A](remote: Remote[_]): Remote[A] = remote.asInstanceOf[Remote[A]]

  def applyFunction[R, E, A, B](f: Remote[A] => ZFlow[R, E, B], env: SchemaAndValue[R]): ZFlow[A, E, B] =
    ZFlow.Apply(remoteA => f(remoteA).provide(env.toRemote))

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

  def start[E: Schema, A: Schema](uniqueId: String, flow: ZFlow[Any, E, A]): UIO[DurablePromise[E, A]] = {
    import zio.flow.ZFlow._
    def step(ref: Ref[State[E, A]]): IO[IOException, Unit] = {

      def onSuccess(value: Remote[_]): IO[IOException, Unit] =
        ref.get.flatMap { state =>
          state.stack match {
            case Nil =>
              eval(value).flatMap { schemaAndValue =>
                state.result
                  .succeed(schemaAndValue.value.asInstanceOf[A])
                  .unit
                  .provideEnvironment(promiseEnv)
              }
            case Instruction.PopEnv :: newStack =>
              ref.update(state => state.copy(stack = newStack, envStack = state.envStack.tail)) *>
                onSuccess(value)
            case Instruction.PushEnv(env) :: newStack =>
              eval(env).flatMap { schemaAndValue =>
                ref.update(state => state.copy(stack = newStack, envStack = schemaAndValue :: state.envStack))
              } *> onSuccess(value)
            case Instruction.Continuation(_, onSuccess) :: newStack =>
              ref.update(_.copy(current = onSuccess.provide(coerceRemote(value)), stack = newStack)) *>
                step(ref)
            case Instruction.PopFallback :: newStack =>
              ref.update(state =>
                state.copy(stack = newStack, tstate = state.tstate.popFallback.getOrElse(state.tstate))
              ) *> onSuccess(value) //TODO : Fail in an elegant way
          }
        }

      def onError(value: Remote[_]): IO[IOException, Unit] =
        ref.get.flatMap { state =>
          state.stack match {
            case Nil =>
              eval(value).flatMap { schemaAndValue =>
                state.result
                  .fail(schemaAndValue.value.asInstanceOf[E])
                  .unit
                  .provideEnvironment(promiseEnv)
              }
            case Instruction.PopEnv :: newStack =>
              ref.update(state => state.copy(stack = newStack, envStack = state.envStack.tail)) *>
                onError(value)
            case Instruction.PushEnv(env) :: newStack =>
              eval(env).flatMap { schemaAndValue =>
                ref.update(state => state.copy(stack = newStack, envStack = schemaAndValue :: state.envStack))
              } *> onError(value)
            case Instruction.Continuation(onError, _) :: newStack =>
              ref.update(_.copy(current = onError.provide(coerceRemote(value)), stack = newStack)) *>
                step(ref)
            case Instruction.PopFallback :: newStack =>
              ref.update(state =>
                state.copy(stack = newStack, tstate = state.tstate.popFallback.getOrElse(state.tstate))
              ) *> onError(value) //TODO : Fail in an elegant way
          }
        }

      ref.get.flatMap(state =>
        state.current match {
          case Return(value) =>
            onSuccess(value)

          case Now =>
            clock.instant.flatMap { currInstant =>
              onSuccess(coerceRemote(lit(currInstant)))
            }

          case Input() =>
            ref.get.flatMap { state =>
              onSuccess(state.currentEnvironment.toRemote)
            }

          case WaitTill(instant) =>
            val wait = for {
              start <- clock.instant
              end   <- eval(instant).map(_.value)
              _     <- clock.sleep(Duration.between(start, end))
            } yield ()
            wait *> onSuccess(())

          case Modify(svar, f0) =>
            def deserializeDurablePromise(bytes: Chunk[Byte]): DurablePromise[_, _] =
              ??? // TODO: implement deserialization of durable promise

            def resume[A](variableName: String, oldValue: A, newValue: A): UIO[Unit] =
              if (oldValue == newValue)
                ZIO.unit
              else
                kvStore
                  .scanAll(s"_zflow_suspended_workflows_readVar_$variableName")
                  .foreach { case (_, value) =>
                    val durablePromise = deserializeDurablePromise(value)
                    durablePromise
                      .asInstanceOf[DurablePromise[Nothing, Unit]]
                      .succeed(())
                      .provideEnvironment(promiseEnv)
                  }
                  .orDie // TODO: handle errors looking up from key value store

            val f = f0.asInstanceOf[Remote[Any] => Remote[(A, Any)]]
            val a: ZIO[Any, Nothing, A] = for {
              vRefTuple <- eval(svar).map(_.value.asInstanceOf[(String, Ref[Any])])
              value     <- vRefTuple._2.get
              tuple     <- eval(f(lit(value))).map(_.value)
              _         <- vRefTuple._2.set(tuple._2)
              _         <- ref.update(_.addReadVar(vRefTuple._1))
              _         <- resume(vRefTuple._1, value, tuple._1)
            } yield tuple._1

            a.flatMap { r =>
              onSuccess(r)
            }

          case apply @ Apply(_) =>
            ref.update { state =>
              val env = state.currentEnvironment
              state.copy(current = apply.lambda(env.toRemote.asInstanceOf[Remote[apply.ValueA]]))
            } *> step(ref)

          case fold @ Fold(_, _, _) =>
            ref.update { state =>
              val cont = Instruction.Continuation(fold.ifError, fold.ifSuccess)
              state.copy(current = fold.value, stack = cont :: state.stack)
            } *> step(ref)

          case RunActivity(input, activity) =>
            val a = for {
              inp    <- eval(input)
              output <- opExec.execute(inp.value, activity.operation)
              _      <- ref.update(_.addCompensation(activity.compensate.provide(lit(output))))
            } yield output

            a.foldZIO(
              error => onError(lit(error)),
              success => onSuccess(lit(success))
            )

          case Transaction(flow) =>
            val env = state.currentEnvironment.value
            for {
              _ <- ref.update(
                     _.enterTransaction(flow.provide(lit(env.asInstanceOf)))
                   ) // TODO : Casting to Nothing will fail
              _ <- ref.update(_.copy(current = flow))
              _ <- step(ref)
            } yield ()

          case Ensuring(flow, finalizer) =>
            ref.get.flatMap { state =>
              val cont = Instruction.Continuation[Any, Any, Any](
                ZFlow.input[Any].flatMap(e => finalizer *> ZFlow.fail(e)),
                ZFlow.input[Any].flatMap(a => finalizer *> ZFlow.succeed(a))
              )
              ref.update(_.copy(current = flow, stack = cont :: state.stack)) *>
                step(ref)
            }

          case Unwrap(remote) =>
            (for {
              evaluatedFlow <- eval(remote)
              _             <- ref.update(_.copy(current = evaluatedFlow.value))
            } yield ()) *> step(ref)

          case fork @ Fork(workflow) =>
            for {
              forkId <- ref.modify { state =>
                          val forkId       = uniqueId + s"_fork_${state.forkCounter}"
                          val updatedState = state.copy(forkCounter = state.forkCounter + 1)
                          (forkId, updatedState)
                        }
              resultPromise <- start[fork.ValueE, fork.ValueA](
                                 forkId,
                                 workflow.asInstanceOf[ZFlow[Any, fork.ValueE, fork.ValueA]]
                               )(fork.schemaE, fork.schemaA)
              _ <- onSuccess(ExecutingFlow(forkId, resultPromise))
            } yield ()

          case Timeout(flow, duration) =>
            ref.get.flatMap { state =>
              for {
                d <- eval(duration).map(_.value)
                output <-
                  ref.update(_.copy(current = flow)) *> step(ref).timeout(d).provideEnvironment(ZEnvironment(clock))
                _ <- output match {
                       case Some(value) =>
                         state.result.succeed(value.asInstanceOf).provideEnvironment(promiseEnv)
                       case None => state.result.succeed(().asInstanceOf).provideEnvironment(promiseEnv)
                     }
              } yield ()
            }

          case Provide(value, flow) =>
            eval(value).flatMap { schemaAndValue =>
              ref.update(state =>
                state.copy(
                  current = flow,
                  stack = Instruction.PopEnv :: state.stack,
                  envStack = schemaAndValue :: state.envStack
                )
              )
            } *> step(ref)

          case Die => ZIO.die(new IllegalStateException("Could not evaluate ZFlow"))

          case RetryUntil =>
            def storeSuspended(
              readVars: Set[String],
              durablePromise: DurablePromise[Nothing, Unit]
            ): IO[IOException, Unit] = {
              def namespace(readVar: String): String =
                s"_zflow_suspended_workflows_readVar_$readVar"

              def key(durablePromise: DurablePromise[Nothing, Unit]): Chunk[Byte] =
                Chunk.fromArray(durablePromise.promiseId.getBytes)

              def value(durablePromise: DurablePromise[Nothing, Unit]): Chunk[Byte] =
                ??? // TODO : Implement serialization of DurablePromise

              ZIO.foreachDiscard(readVars)(readVar =>
                kvStore.put(namespace(readVar), key(durablePromise), value(durablePromise))
              )
            }

            ref.modify { state =>
              state.tstate match {
                case TState.Empty =>
                  ZIO.unit -> state.copy(current = ZFlow.unit)
                case transaction @ TState.Transaction(_, _, _, fallback :: fallbacks) =>
                  val tstate = transaction.copy(fallbacks = fallbacks)
                  ZIO.unit -> state.copy(current = fallback, tstate = tstate)
                case TState.Transaction(_, readVars, _, Nil) =>
                  val durablePromise = DurablePromise.make[Nothing, Unit](
                    s"_zflow_workflow_${state.workflowId}_durablepromise_${state.promiseIdCounter}"
                  )
                  storeSuspended(readVars, durablePromise) *>
                    durablePromise
                      .awaitEither(Schema.fail("nothing schema"), Schema[Unit])
                      .provideEnvironment(promiseEnv) ->
                    state.copy(
                      current = ZFlow.unit,
                      compileStatus = PersistentCompileStatus.Suspended,
                      promiseIdCounter = state.promiseIdCounter + 1
                    )
              }
            }.flatten *> step(ref)

          case OrTry(left, right) =>
            for {
              state <- ref.get
              _ <- state.tstate.addFallback(erase(right).provide(state.currentEnvironment.toRemote)) match {
                     case None => ZIO.dieMessage("The OrTry operator can only be used inside transactions.")
                     case Some(tstate) =>
                       ref.set(
                         state.copy(current = left, tstate = tstate, stack = Instruction.PopFallback :: state.stack)
                       ) *> step(ref)
                   }
            } yield ()

          case Await(execFlow) =>
            for {
              executingFlow <- eval(execFlow).map(_.value)
              _ <- ZIO.log("Waiting for result")
              result <-
                executingFlow.result.asInstanceOf[DurablePromise[E, A]].awaitEither.provideEnvironment(promiseEnv)
                  .tapErrorCause(c => ZIO.log(s"Failed: $c"))
              _ <- ZIO.log(s"Await got result: $result")
              _ <- result.fold(
                     error => onError(lit(error)),
                     success => onSuccess(success)
                   )
            } yield ()

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
            val variable = for {
              schemaAndValue <- eval(initial)
              vref           <- Ref.make(schemaAndValue.value.asInstanceOf[A])
              _              <- ref.update(_.addVariable(name, schemaAndValue))
            } yield vref

            variable.flatMap(vref => onSuccess(lit((name, vref))))

          case Iterate(initial, step0, predicate) =>
            ref.modify { state =>
              val tempVarCounter = state.tempVarCounter
              val tempVarName    = s"_zflow_tempvar_${tempVarCounter}"

              def iterate[R, E, A](
                step: Remote[A] => ZFlow[R, E, A],
                predicate: Remote[A] => Remote[Boolean],
                stateVar: Remote[Variable[A]],
                boolRemote: Remote[Boolean]
              ): ZFlow[R, E, A] =
                ZFlow.ifThenElse(boolRemote)(
                  stateVar.get.flatMap { a =>
                    step(a).flatMap { a =>
                      stateVar.set(a).flatMap { _ =>
                        val boolRemote = predicate(a)
                        iterate(step, predicate, stateVar, boolRemote)
                      }
                    }
                  },
                  stateVar.get
                )

              val zFlow = for {
                stateVar   <- ZFlow.newVar(tempVarName, initial)
                stateValue <- stateVar.get
                boolRemote <- predicate(stateValue)
                _          <- ZFlow.log(s"stateValue = $stateValue")
                _          <- ZFlow.log(s"boolRemote = $boolRemote")
                _          <- ZFlow.log(s"boolRemote = $boolRemote")
                stateValue <- iterate(step0, predicate, stateVar, boolRemote)
              } yield stateValue

              val updatedState = state.copy(current = zFlow, tempVarCounter = tempVarCounter + 1)

              step(ref) -> updatedState
            }.flatten

          case Log(message) =>
            ZIO.log(message) *> onSuccess(())

          case GetExecutionEnvironment =>
            onSuccess(lit(execEnv))
        }
      )
    }

    val durablePromise =
      DurablePromise.make[E, A](uniqueId + "_result")
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
      _   <- step(ref).fork
      // TODO: store running workflow and it's fiber
    } yield state.result
  }

  def submit[E: Schema, A: Schema](uniqueId: String, flow: ZFlow[Any, E, A]): IO[E, A] =
    (for {
      resultPromise <- start(uniqueId, flow)
      result        <- resultPromise.awaitEither.provideEnvironment(promiseEnv)
    } yield result).orDie.absolve
}

object PersistentExecutor {

  sealed trait Instruction

  object Instruction {

    case object PopEnv extends Instruction

    final case class PushEnv(env: Remote[_]) extends Instruction

    final case class Continuation[A, E, B](onError: ZFlow[E, E, B], onSuccess: ZFlow[A, E, B]) extends Instruction

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

  final case class State[E, A](
    workflowId: String,
    current: ZFlow[_, _, _],
    tstate: TState,
    stack: List[Instruction],
    variables: Map[String, SchemaAndValue[
      _
    ]], //TODO : change the _ to SchemaAndValue[_]. may not get compile error from this change.
    result: DurablePromise[E, A],
    envStack: List[SchemaAndValue[_]],
    tempVarCounter: Int,
    promiseIdCounter: Int,
    forkCounter: Int,
    retry: List[FlowDurablePromise[_, _]],
    compileStatus: PersistentCompileStatus
  ) {

    def currentEnvironment: SchemaAndValue[_] = envStack.headOption.getOrElse(SchemaAndValue[Unit](Schema[Unit], ()))

    def pushEnv(schemaAndValue: SchemaAndValue[_]): State[E, A] = copy(envStack = schemaAndValue :: envStack)

    def pushInstruction(instruction: Instruction): State[E, A] = copy(stack = instruction :: stack)

    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): State[E, A] =
      copy(tstate = tstate.addCompensation(newCompensation))

    def addReadVar(name: String): State[E, A] =
      copy(tstate = tstate.addReadVar(name))

    def addVariable(name: String, value: SchemaAndValue[Any]): State[E, A] =
      copy(variables = variables + (name -> value))

    def enterTransaction(flow: ZFlow[Any, _, _]): State[E, A] = copy(tstate = tstate.enterTransaction(flow))

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

}

sealed trait TState {
  self =>
  def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): TState = self match {
    case TState.Empty => TState.Empty
    case TState.Transaction(flow, readVars, compensation, fallBacks) =>
      TState.Transaction(flow, readVars, newCompensation *> compensation, fallBacks)
    //TODO : Compensation Failure semantics
  }

  def addReadVar(name: String): TState = self match {
    case TState.Empty => TState.Empty
    case TState.Transaction(flow, readVars, compensation, fallbacks) =>
      TState.Transaction(flow, readVars + name, compensation, fallbacks)
  }

  def allVariables: Set[String] = self match {
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
    readVars: Set[String],
    compensation: ZFlow[Any, ActivityError, Any],
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
