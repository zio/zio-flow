package zio.flow.server

import java.io.IOException
import java.time.Duration

import zio._
import zio.clock._
import zio.console.putStrLn
import zio.flow._
import zio.flow.remote.{Remote, SchemaAndValue}
import zio.flow.zFlow.{ZFlow, ZFlowExecutor}
import zio.schema.DeriveSchema.gen
import zio.schema._

final case class PersistentExecutor(
                                     clock: Clock.Service,
                                     durableLog: DurableLog,
                                     kvStore: KeyValueStore,
                                     opExec: OperationExecutor[Any],
                                     workflows: Ref[Map[String, Ref[PersistentExecutor.State[_, _]]]]
                                   ) extends ZFlowExecutor[String] {

  import PersistentExecutor._

  type Erased = ZFlow[Any, Any, Any]
  type ErasedCont = Remote[Any] => ZFlow[Any, Any, Any]

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
      map <- workflows.get
      stateRef <- ZIO.fromOption(map.get(workflowId))
      state <- stateRef.get
      value <- ZIO.fromOption(state.getVariable(variableName))
    } yield value).optional

  def setVariable(workflowId: String, variableName: String, value: SchemaAndValue[_]): UIO[Boolean] =
    (for {
      map <- workflows.get
      stateRef <- ZIO.fromOption(map.get(workflowId))
      _ <- stateRef.update(state => state.copy(variables = state.variables.updated(variableName, value)))
      // TODO: It is time to retry a workflow that is suspended, because a variable changed.
      // _        <- stateRef.modify(state => (state.retry.forkDaemon, state.copy(retry = ZIO.unit))).flatten
    } yield true).catchAll(_ => UIO(false))

  def submit[E: Schema, A: Schema](uniqueId: String, flow: ZFlow[Any, E, A]): IO[E, A] = {
    import zio.flow.zFlow.ZFlow._
    def step(ref: Ref[State[E, A]]): IO[IOException, Unit] = {

      def onSuccess(value: Remote[_]): IO[IOException, Unit] =
        ref.get.flatMap { state =>
          state.stack match {
            case Nil =>
              eval(value).flatMap { schemaAndValue =>
                state.result.succeed(schemaAndValue.value.asInstanceOf[A]).unit
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
            case Instruction.PopFallback :: newStack => ref.update(state => state.copy(stack = newStack, tstate = state.tstate.popFallback.getOrElse(state.tstate))) *> onSuccess(value) //TODO : Fail in an elegant way
          }
        }

      def onError(value: Remote[_]): IO[IOException, Unit] =
        ref.get.flatMap { state =>
          state.stack match {
            case Nil =>
              eval(value).flatMap { schemaAndValue =>
                state.result.fail(schemaAndValue.value.asInstanceOf[E]).unit
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
            case Instruction.PopFallback :: _ =>
              ???
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
              end <- eval(instant).map(_.value)
              _ <- clock.sleep(Duration.between(start, end))
            } yield ()
            wait *> onSuccess(())

          case Modify(svar, f0) =>
            val f = f0.asInstanceOf[Remote[Any] => Remote[(A, Any)]]
            val a: ZIO[Any, Nothing, A] = for {
              vRefTuple <- eval(svar).map(_.value.asInstanceOf[(String, Ref[Any])])
              value <- vRefTuple._2.get
              tuple <- eval(f(lit(value))).map(_.value)
              _ <- vRefTuple._2.set(tuple._2)
              _ <- ref.update(_.addReadVar(vRefTuple._1))
            } yield tuple._1

            a.flatMap { r =>
              onSuccess(r)
            }

          case apply@Apply(_) =>
            ref.update { state =>
              val env = state.currentEnvironment
              state.copy(current = apply.lambda(env.toRemote.asInstanceOf[Remote[apply.ValueA]]))
            } *> step(ref)

          case fold@Fold(_, _, _) =>
            ref.update { state =>
              val cont = Instruction.Continuation(fold.ifError, fold.ifSuccess)
              state.copy(current = fold.value, stack = cont :: state.stack)
            } *> step(ref)

          case RunActivity(input, activity) =>
            val a = for {
              inp <- eval(input)
              output <- opExec.execute(inp.value, activity.operation)
              _ <- ref.update(_.addCompensation(activity.compensate.provide(lit(output))))
            } yield output

            a.foldM(
              error => onError(lit(error)),
              success => onSuccess(lit(success))
            )

          case Transaction(flow) =>
            val env = state.currentEnvironment.value
            for {
              _ <- ref.update(_.enterTransaction(flow.provide(lit(env.asInstanceOf)))) // TODO : Casting to Nothing will fail
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
              _ <- ref.update(_.copy(current = evaluatedFlow.value))
            } yield ()) *> step(ref)

          case foreach@Foreach(_, _) => ???

          case Fork(workflow) =>
            val fiber = for {
              _ <- ref.update(_.copy(current = workflow))
              f <- step(ref).fork
            } yield f

            fiber.flatMap(f => onSuccess(f.asInstanceOf))

          case Timeout(flow, duration) =>
            ref.get.flatMap { state =>
              for {
                d <- eval(duration).map(_.value)
                output <- ref.update(_.copy(current = flow)) *> step(ref).timeout(d).provide(Has(clock))
                _ <- output match {
                  case Some(value) => state.result.succeed(value.asInstanceOf)
                  case None => state.result.succeed(().asInstanceOf)
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

          case RetryUntil => ???
            // TODO : get the tstate. If there s a fallback, do it right away. Otherwise, enter suspended mode.(Set the durable promise)
            //TODO : Modify alters durable promise.
            //TODO : Some way of resuming a workflow (from the outside)

//            //TODO : Implement something like compileStatus in State
//            for {
//              state <- ref.get
//              _ <- state.getTransactionFlow match {
//                case Some(flow) =>
//                  ref.update(
//                    _.addRetry(FlowDurablePromise(flow.asInstanceOf[ZFlow[Any, E, A]], state.result))
//                      .setSuspended()
//                  )
//
//                case None => ZIO.dieMessage("There is no transaction to retry.")
//              }
//            } yield ()

          case OrTry(left, right) => ???
            // for {
            //   state <- ref.get
            //   _ <- state.tstate.addFallback(right.provide(state.currentEnvironment.value)) match {
            //     case None => ZIO.dieMessage("The OrTry operator can only be used inside transactions.")
            //     case Some(tstate) => ref.set(state.copy(current = left, tstate = tstate, stack = Instruction.PopFallback :: state.stack)) *> step(ref)
            //   }
            // } yield ()

          case Await(execFlow) =>
            val joined = for {
              execflow <- eval(execFlow).map(_.asInstanceOf[Fiber[E, A]])
              result <- execflow.join
            } yield result

            joined.foldM(
              error => onError(lit(error)),
              success => onSuccess(success)
            )

          case Interrupt(execFlow) =>
            val interrupt = for {
              exec <- eval(execFlow).map(_.asInstanceOf[Fiber[E, A]])
              exit <- exec.interrupt
            } yield exit.toEither

            interrupt.flatMap {
              case Left(error) => onError(lit(error))
              case Right(success) => onSuccess(success)
            }

          case Fail(error) =>
            onError(error)

          case NewVar(name, initial) =>
            val variable = for {
              schemaAndValue <- eval(initial)
              vref <- Ref.make(schemaAndValue.value.asInstanceOf[A])
              _ <- ref.update(_.addVariable(name, schemaAndValue))
            } yield vref

            variable.flatMap(vref => onSuccess(lit((name, vref))))

          case Iterate(initial, step0, predicate) =>
            ref.modify { state =>
              val tempVarCounter = state.tempVarCounter
              val tempVarName = s"_zflow_tempvar_${tempVarCounter}"

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
            val log = putStrLn(message).provideLayer(zio.console.Console.live)

            log *> onSuccess(())

          case Fold2(_, _, _) => ???
        }
      )
    }

    val durablePZio =
      Promise.make[E, A].map(promise => DurablePromise.make[E, A](uniqueId + "_result", durableLog, promise))
    val stateZio = durablePZio.map(dp =>
      State(uniqueId, flow, TState.Empty, Nil, Map(), dp, Nil, 0, Nil, PersistentCompileStatus.Running)
    )

    (for {
      state <- stateZio
      ref <- Ref.make[State[E, A]](state)
      _ <- step(ref)
      result <- state.result.awaitEither
    } yield result).orDie.absolve
  }
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
            opEx: OperationExecutor[Any]
          ): ZLayer[Clock with Has[DurableLog] with Has[KeyValueStore], Nothing, Has[ZFlowExecutor[String]]] =
    (
      for {
        durableLog <- ZIO.service[DurableLog]
        kvStore <- ZIO.service[KeyValueStore]
        clock <- ZIO.service[Clock.Service]
        ref <- Ref.make[Map[String, Ref[PersistentExecutor.State[_, _]]]](Map.empty)
      } yield PersistentExecutor(clock, durableLog, kvStore, opEx, ref)
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
                                retry: List[FlowDurablePromise[_, _]],
                                compileStatus: PersistentCompileStatus
                              ) {

    def currentEnvironment: SchemaAndValue[_] = envStack.headOption.getOrElse(SchemaAndValue[Unit](Schema[Unit], ()))

    def pushEnv(schemaAndValue: SchemaAndValue[_]): State[E, A] = copy(envStack = schemaAndValue :: envStack)

    def pushInstruction(instruction : Instruction) : State[E,A] = copy(stack = instruction :: stack)

    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): State[E, A] =
      copy(tstate = tstate.addCompensation(newCompensation))

    def addReadVar(name: String): State[E, A] =
      copy(tstate = tstate.addReadVar(name))

    def addVariable(name: String, value: SchemaAndValue[Any]): State[E, A] =
      copy(variables = variables + (name -> value))

    def enterTransaction(flow: ZFlow[Any, _, _]): State[E, A] = copy(tstate = tstate.enterTransaction(flow))

    def getTransactionFlow: Option[ZFlow[Any, _, _]] = tstate match {
      case TState.Empty => None
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
    case TState.Transaction(flow, readVars, compensation, fallbacks) => TState.Transaction(flow, readVars + name, compensation, fallbacks)
  }

  def allVariables: Set[String] = self match {
    case TState.Empty => Set()
    case TState.Transaction(_, readVars, _, _) => readVars
  }

  def enterTransaction(flow: ZFlow[Any, _, _]): TState =
    self match {
      case TState.Empty => TState.Transaction(flow, Set(), ZFlow.unit, Nil)
      case _ => self
    }

  def addFallback(zflow: ZFlow[Any, _, _]): Option[TState] =
    self match {
      case TState.Empty => None
      case tstate@TState.Transaction(_, _, _, fallBacks) => Some(tstate.copy(fallBacks = zflow :: fallBacks))
    }

  def popFallback : Option[TState] =
    self match {
      case TState.Empty => None
      case tstate @ TState.Transaction(_, _, _, fallBacks) => Some(tstate.copy(fallBacks = fallBacks.drop(1)))
    }
}

object TState {

  case object Empty extends TState

  final case class Transaction(
                                flow: ZFlow[Any, _, _],
                                readVars: Set[String],
                                compensation: ZFlow[Any, ActivityError, Any],
                                fallBacks: List[ZFlow[Any, _, _]]
                              ) extends TState

}

final case class FlowDurablePromise[E, A](flow: ZFlow[Any, E, A], promise: DurablePromise[E, A])

sealed trait PersistentCompileStatus

object PersistentCompileStatus {

  case object Running extends PersistentCompileStatus

  case object Done extends PersistentCompileStatus

  case object Suspended extends PersistentCompileStatus

}
