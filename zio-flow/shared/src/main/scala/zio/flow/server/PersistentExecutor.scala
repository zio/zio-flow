package zio.flow.server

import java.io.IOException
import java.time.Duration

import zio._
import zio.clock._
import zio.console.putStrLn
import zio.flow._
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
  import ZFlow._

  type Erased     = ZFlow[Any, Any, Any]
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
    ZFlow.input[A].flatMap(a => f(a).provide(env.toRemote))

  //
  //    // 1. Read the environment `A` => ZFlow.Input (peek the environment)
  //    // 2. Push R onto the environment
  //    // 3. Run the workflow returned by `f`
  //
  //  state.update { state =>
  //    val cont = Continuation.handleSuccess(ZFlow.input[A])
  //    state.copy(stack = cont :: state.stack)
  //
  //  }

  def getVariable(workflowId: String, variableName: String): UIO[Option[Any]] =
    (for {
      map      <- workflows.get
      stateRef <- ZIO.fromOption(map.get(workflowId))
      state    <- stateRef.get
      value    <- ZIO.fromOption(state.getVariable(variableName))
    } yield value).optional

  def setVariable(workflowId: String, variableName: String, value: SchemaAndValue[_]): UIO[Boolean] =
    (for {
      map      <- workflows.get
      stateRef <- ZIO.fromOption(map.get(workflowId))
      _        <- stateRef.update(state => state.copy(variables = state.variables.updated(variableName, value)))
      // TODO: It is time to retry a workflow that is suspended, because a variable changed.
      // _        <- stateRef.modify(state => (state.retry.forkDaemon, state.copy(retry = ZIO.unit))).flatten
    } yield true).catchAll(_ => UIO(false))

  def submit[E: Schema, A: Schema](uniqueId: String, flow: ZFlow[Any, E, A]): IO[E, A] = {
    def step(ref: Ref[State[E, A]]): IO[IOException, Unit] = {

      def onSuccess(value: Remote[_]): IO[IOException, Unit] =
        ref.get.flatMap { state =>
          state.stack match {
            case Nil                                   =>
              eval(value).flatMap { schemaAndValue =>
                state.result.succeed(schemaAndValue.value.asInstanceOf[A]).unit
              }
            case Continuation.PopEnv :: newStack       =>
              ref.update(state => state.copy(stack = newStack, envStack = state.envStack.tail)) *>
                onSuccess(value)
            case Continuation.PushEnv(env) :: newStack =>
              eval(env).flatMap { schemaAndValue =>
                ref.update(state => state.copy(stack = newStack, envStack = schemaAndValue :: state.envStack))
              } *> onSuccess(value)
            case k :: newStack                         =>
              ref.update(_.copy(current = k.onSuccess.provide(coerceRemote(value)), stack = newStack)) *>
                step(ref)
          }
        }

      def onError(value: Remote[_]): IO[IOException, Unit] =
        ref.get.flatMap { state =>
          state.stack match {
            case Nil                                   =>
              eval(value).flatMap { schemaAndValue =>
                state.result.fail(schemaAndValue.value.asInstanceOf[E]).unit
              }
            case Continuation.PopEnv :: newStack       =>
              ref.update(state => state.copy(stack = newStack, envStack = state.envStack.tail)) *>
                onError(value)
            case Continuation.PushEnv(env) :: newStack =>
              eval(env).flatMap { schemaAndValue =>
                ref.update(state => state.copy(stack = newStack, envStack = schemaAndValue :: state.envStack))
              } *> onError(value)
            case k :: newStack                         =>
              ref.update(_.copy(current = k.onError.provide(coerceRemote(value)), stack = newStack)) *>
                step(ref)
          }
        }

      ref.get.flatMap(state =>
        state.current match {
          case Return(value) =>
            onSuccess(value)

          case Now =>
            clock.instant.flatMap { currInstant =>
              onSuccess(currInstant)
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
            val f                       = f0.asInstanceOf[Remote[Any] => Remote[(A, Any)]]
            val a: ZIO[Any, Nothing, A] = for {
              vRef  <- eval(svar).map(_.asInstanceOf[Ref[Any]])
              value <- vRef.get
              tuple <- eval(f(lit(value))).map(_.value)
              _     <- vRef.set(tuple._2)
              _     <- ref.update(_.addReadVar(vRef))
            } yield tuple._1

            a.flatMap { r =>
              onSuccess(r)
            }

          case fold @ Fold(Input(), _, _) =>
            ref.update { state =>
              val env = state.currentEnvironment
              state.copy(current = fold.ifSuccess(env.toRemote))
            } *> step(ref)

          case fold @ Fold(_, _, _) =>
            ref.update { state =>
              val env         = state.currentEnvironment
              val errorFlow   = applyFunction(eraseCont(fold.ifError), env)
              val successFlow = applyFunction(eraseCont(fold.ifSuccess), env)
              val cont        = Continuation(errorFlow, successFlow)
              state.copy(current = fold.value, stack = cont :: state.stack)
            } *> step(ref)

          case RunActivity(input, activity) =>
            val a = for {
              inp    <- eval(input)
              output <- opExec.execute(inp.value, activity.operation)
              _      <- ref.update(_.addCompensation(activity.compensate.provide(lit(output))))
            } yield output

            a.foldM(
              error => onError(lit(error)),
              success => onSuccess(lit(success))
            )

          case Transaction(flow) =>
            val env = state.currentEnvironment.value
            for {
              _ <- ref.update(_.enterTransaction(flow.provide(lit(env.asInstanceOf))))
              _ <- ref.update(_.copy(current = flow))
              _ <- step(ref)
            } yield ()

          case Ensuring(flow, finalizer) =>
            ref.get.flatMap { state =>
              val cont = Continuation(
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

          case foreach @ Foreach(_, _) => ???

          case Fork(workflow) =>
            val fiber = for {
              _ <- ref.update(_.copy(current = workflow))
              f <- step(ref).fork
            } yield f

            fiber.flatMap(f => onSuccess(f.asInstanceOf))

          case Timeout(flow, duration) =>
            ref.get.flatMap { state =>
              for {
                d      <- eval(duration).map(_.value)
                output <- ref.update(_.copy(current = flow)) *> step(ref).timeout(d).provide(Has(clock))
                _      <- output match {
                            case Some(value) => state.result.succeed(value.asInstanceOf)
                            case None        => state.result.succeed(().asInstanceOf)
                          }
              } yield ()
            }

          //TODO : instead of Provide, use ZFlow.pushEnv and ZFlow.popEnv. Implement Provide in terms of pushEnv, popEnv, Ensuring
          case Provide(value, flow)    =>
            eval(value).flatMap { schemaAndValue =>
              ref.update(state =>
                state.copy(
                  current = flow,
                  stack = Continuation.PopEnv :: state.stack,
                  envStack = schemaAndValue :: state.envStack
                )
              )
            } *> step(ref)

          case Die => ZIO.die(new IllegalStateException("Could not evaluate ZFlow"))

          case RetryUntil =>
            for {
              state <- ref.get
              _     <- state.getTransactionFlow match {
                         case Some(flow) =>
                           ref.update(
                             _.addRetry(FlowDurablePromise(flow.asInstanceOf[ZFlow[Any, E, A]], state.result)).setSuspended()
                           )

                         case None => ZIO.dieMessage("There is no transaction to retry.")
                       }
            } yield ()

          case OrTry(left, right) =>
            ref.set(state.copy(current = left)) *> step(ref) *>
              ref.get.flatMap { state =>
                for {
                  _ <- {
                    val cont = Continuation(ZFlow.unit, right)
                    ref.set(state.copy(stack = cont :: state.stack)) *>
                      step(ref)
                  }.when(state.compileStatus == PersistentCompileStatus.Suspended)
                } yield ()
              }

          case Await(execFlow) =>
            val joined = for {
              execflow <- eval(execFlow).map(_.asInstanceOf[Fiber[E, A]])
              result   <- execflow.join
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
              case Left(error)    => onError(lit(error))
              case Right(success) => onSuccess(success)
            }

          case Fail(error) =>
            onError(error)

            case NewVar(name, initial) =>
            val variable = for {
              schemaAndValue <- eval(initial)
              vref           <- Ref.make(schemaAndValue.value)
              _              <- ref.update(_.addVariable(name, schemaAndValue))
            } yield vref

            variable.flatMap(vref => onSuccess(vref.asInstanceOf))

          case iterate0 @ Iterate(_, _, _) => ???
          //TODO :
          //1. create a variable to hold an A (state type)
          //2. evaluate the initial A
          //3. store the A inside the variable
          //4. begin the loop
          // 4.1 Test the predicate on the variable `A`
          // 4.2 If the predicate is true, :
          //    4.2.1 then update the current flow to the flow we get from step function
          //    4.2.2 push a new continuation to the stack that will continue the loop
          //  4.3 If the predicate is false,:
          //      4.3.1 get the value of the temp variable
          //      4.3.2 delete the temp variable
          //      4.3.3 inspect the stack - terminate with  a value (complete the promise) or continue by feeding this value into continuation

          //            ref.modify { state =>
          //              val tempVarName = s"_zflow_tempvar_${state.tempVarCounter}"
          //              val newState = state.copy(tempVarCounter = state.tempVarCounter + 1) //TODO Add helper
          //              val zflow = for {
          //                stateVar <- ZFlow.newVar(tempVarName, iterate0.initial).asInstanceOf[RemoteVariable[A]]
          //                stateValue <- stateVar.get
          //                boolRemote = iterate0.predicate(stateValue.asInstanceOf)
          //                //stateValue <- ZFlow.ifThenElse(boolRemote)(ifTrue = ???, ifFalse = ???)
          //              } yield stateValue
          //
          //              (zflow, newState)
          //            }.flatMap(zflow => ref.update(_.copy(current = zflow)) *> step(ref))

          case Log(message) =>
            val log = putStrLn(message).provideLayer(zio.console.Console.live)

            log *> onSuccess(())
        }
      )
    }

    val durablePZio =
      Promise.make[E, A].map(promise => DurablePromise.make[E, A](uniqueId + "_result", durableLog, promise))
    val stateZio    = durablePZio.map(dp =>
      State(uniqueId, flow, TState.Empty, Nil, Map(), dp, Nil, 0, Nil, PersistentCompileStatus.Running)
    )

    (for {
      state  <- stateZio
      ref    <- Ref.make[State[E, A]](state)
      _      <- step(ref)
      result <- state.result.awaitEither
    } yield result).orDie.absolve
  }
}

object PersistentExecutor {

  sealed trait Continuation {
    def onError: ZFlow[_, _, _]
    def onSuccess: ZFlow[_, _, _]
  }

  object Continuation {

    case object PopEnv extends Continuation {
      def onError: ZFlow[_, _, _]   = ZFlow.unit
      def onSuccess: ZFlow[_, _, _] = ZFlow.unit
    }

    final case class PushEnv(env: Remote[_]) extends Continuation {
      def onError: ZFlow[_, _, _]   = ZFlow.unit
      def onSuccess: ZFlow[_, _, _] = ZFlow.unit
    }

    def apply[R, E, A](onError0: ZFlow[R, E, A], onSuccess0: ZFlow[R, E, A]): Continuation =
      new Continuation {
        def onError: ZFlow[_, _, _] = onError0
        def onSuccess: ZFlow[_, _, _] = onSuccess0
      }
  }

  def make(
    opEx: OperationExecutor[Any]
  ): ZLayer[Clock with Has[DurableLog] with Has[KeyValueStore], Nothing, Has[ZFlowExecutor[String]]] =
    (
      for {
        durableLog <- ZIO.service[DurableLog]
        kvStore    <- ZIO.service[KeyValueStore]
        clock      <- ZIO.service[Clock.Service]
        ref        <- Ref.make[Map[String, Ref[PersistentExecutor.State[_, _]]]](Map.empty)
      } yield PersistentExecutor(clock, durableLog, kvStore, opEx, ref)
    ).toLayer

  final case class State[E, A](
    workflowId: String,
    current: ZFlow[_, _, _],
    tstate: TState,
    stack: List[Continuation],
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

    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): State[E, A] =
      copy(tstate = tstate.addCompensation(newCompensation))

    def addReadVar(value: Any): State[E, A] =
      copy(tstate = tstate.addReadVar(lookupName(value)))

    def addVariable(name: String, value: SchemaAndValue[Any]): State[E, A] =
      copy(variables = variables + (name -> value))

    def enterTransaction(flow: ZFlow[Any, _, _]): State[E, A]              = copy(tstate = tstate.enterTransaction(flow))

    def getTransactionFlow: Option[ZFlow[Any, _, _]] = tstate match {
      case TState.Empty                   => None
      case TState.Transaction(flow, _, _) => Some(flow)
    }

    def addRetry(flowDurablePromise: FlowDurablePromise[_, _]): State[E, A] = copy(retry = flowDurablePromise :: retry)

    def setSuspended(): State[E, A] = copy(compileStatus = PersistentCompileStatus.Suspended)

    def getVariable(name: String): Option[SchemaAndValue[_]] = variables.get(name)

    //TODO scala map function
    private lazy val lookupName: Map[Any, String] =
      variables.map((t: (String, _)) => t._2 -> t._1)
  }

  sealed trait TState {
    self =>
    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): TState = self match {
      case TState.Empty                                     => TState.Empty
      case TState.Transaction(flow, readVars, compensation) =>
        TState.Transaction(flow, readVars, newCompensation *> compensation)
      //TODO : Compensation Failure semantics
    }

    def addReadVar(name: String): TState = self match {
      case TState.Empty                                     => TState.Empty
      case TState.Transaction(flow, readVars, compensation) => TState.Transaction(flow, readVars + name, compensation)
    }

    def allVariables: Set[String] = self match {
      case TState.Empty                       => Set()
      case TState.Transaction(_, readVars, _) => readVars
    }

    def enterTransaction(flow: ZFlow[Any, _, _]): TState =
      self match {
        case TState.Empty => TState.Transaction(flow, Set(), ZFlow.unit)
        case _            => self
      }
  }

  object TState {

    case object Empty extends TState

    final case class Transaction(
      flow: ZFlow[Any, _, _],
      readVars: Set[String],
      compensation: ZFlow[Any, ActivityError, Any]
    ) extends TState

  }

  final case class FlowDurablePromise[E, A](flow: ZFlow[Any, E, A], promise: DurablePromise[E, A])

  sealed trait PersistentCompileStatus

  object PersistentCompileStatus {

    case object Running extends PersistentCompileStatus

    case object Done extends PersistentCompileStatus

    case object Suspended extends PersistentCompileStatus

  }

}
