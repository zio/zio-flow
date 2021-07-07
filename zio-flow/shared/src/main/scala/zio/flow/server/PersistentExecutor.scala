package zio.flow.server

import java.io.IOException
import zio._
import zio.clock._
import zio.flow._
import zio.schema._

import scala.::

final case class PersistentExecutor(
                                     clock: Clock.Service,
                                     durableLog: DurableLog,
                                     kvStore: KeyValueStore,
                                     opExec: OperationExecutor[Any],
                                     workflows: Ref[Map[String, Ref[PersistentExecutor.State[_, _]]]]
                                   ) extends ZFlowExecutor[String] {

  import PersistentExecutor._
  import ZFlow._

  type Erased = ZFlow[Any, Any, Any]

  def erase(flow: ZFlow[_, _, _]): Erased = flow.asInstanceOf[Erased]

  def eval[A](r: Remote[A]): UIO[SchemaAndValue[A]] = UIO(
    r.evalWithSchema.getOrElse(throw new IllegalStateException("Eval could not be reduced to Right of Either."))
  )

  def lit[A](a: A): Remote[A] =
    Remote.Literal(a, Schema.fail("It is not expected to serialize this value"))

  def coerceRemote[A](remote: Remote[_]): Remote[A] = remote.asInstanceOf[Remote[A]]

  def applyFunction[A: Schema, R, E, B](f: Remote[A] => ZFlow[R, E, B], env: SchemaAndValue[R]): ZFlow[A, E, B] =
    for {
      a <- ZFlow.input[A]
      b <- f(a).provide(env.toRemote)
    } yield b

  def getVariable(workflowId: String, variableName: String): UIO[Option[Any]] =
    (for {
      map <- workflows.get
      stateRef <- ZIO.fromOption(map.get(workflowId))
      state <- stateRef.get
      value <- ZIO.fromOption(state.getVariable(variableName))
    } yield value).optional

  def setVariable(workflowId: String, variableName: String, value: Any): UIO[Boolean] =
    (for {
      map <- workflows.get
      stateRef <- ZIO.fromOption(map.get(workflowId))
      _ <- stateRef.update(state => state.copy(variables = state.variables.updated(variableName, value)))
      // TODO: It is time to retry a workflow that is suspended, because a variable changed.
      // _        <- stateRef.modify(state => (state.retry.forkDaemon, state.copy(retry = ZIO.unit))).flatten
    } yield true).catchAll(_ => UIO(false))

  def submit[E: Schema, A: Schema](uniqueId: String, flow: ZFlow[Any, E, A]): IO[E, A] = {
    def step(ref: Ref[State[E, A]]): IO[IOException, Unit] =
      ref.get.flatMap { state =>
        state.current match {
          case Return(value) =>
            ref.get.flatMap { state =>
              state.stack match {
                case Nil =>

                  eval(value).flatMap { schemaAndValue0 =>
                    val schemaAndValue = schemaAndValue0.asInstanceOf[SchemaAndValue[A]]
                    state.result.succeed(schemaAndValue.value: A).unit
                  }
                case k :: newStack =>
                  ref.update(_.copy(current = k.onSuccess.provide(coerceRemote(value)), stack = newStack)) *>
                    step(ref)
              }
            }

          case Now => ???

          case Input(_) =>
            ref.get.flatMap { state =>
              val env = state.currentEnvironment.value
              state.stack match {
                case k :: newStack => ref.update(_.copy(current = k.onSuccess.provide(coerceRemote(lit(env))), stack = newStack)) *> step(ref)
                case Nil => state.result.succeed(env.asInstanceOf[A]).unit
              }
            }

          case WaitTill(instant) => ???

          case Modify(svar, f0) => ???

          case fold@Fold(_, _, _, _, _) =>

            ref.update { state =>
              val env = state.currentEnvironment
              val errorFlow = applyFunction(fold.ifError, env)(fold.schemaE1)
              val successFlow = applyFunction(fold.ifSuccess, env)(fold.schemaA)
              val cont = Continuation(errorFlow, successFlow)
              state.copy(current = fold.value, stack = cont :: state.stack)
            } *> step(ref)

          case RunActivity(input, activity) => ???

          case Transaction(flow) => ???

          case Ensuring(flow, finalizer) => ???

          case Unwrap(remote) => ???

          case Foreach(values, body) => ???

          case Fork(workflow) => ???

          case Timeout(flow, duration) => ???

          case Provide(value, flow) =>
            eval(value).flatMap { schemaAndValue =>
              ref.update(state =>
                state.pushEnv(schemaAndValue).copy(current = flow)) *> step(ref)
            }

          case Die => ???

          case RetryUntil => ???

          case OrTry(left, right) => ???

          case Await(execFlow) => ???

          case Interrupt(execFlow) => ???

          case Fail(error) => ???

          case NewVar(name, initial) => ???

          case iterate0@Iterate(_, _, _) => ???
        }
      }

    val promise = DurablePromise.make[E, A](uniqueId + "_result", durableLog)
    val state = State(uniqueId, flow, TState.Empty, Nil, Map(), promise, None)

    (for {
      ref <- Ref.make[State[E, A]](state)
      _ <- step(ref)
      result <- promise.awaitEither
    } yield result).orDie.absolve
  }
}

object PersistentExecutor {

  final case class Continuation(
                                 onError: ZFlow[_, _, _],
                                 onSuccess: ZFlow[_, _, _]
                               )

  object Continuation {
    def handleError[E, A: Schema](onError: ZFlow[_, _, _]): Continuation =
      Continuation(onError, ZFlow.input[A].flatMap(success => ZFlow.succeed(success)))

    def handleSuccess[E: Schema, A](onSuccess: ZFlow[_, _, _]): Continuation =
      Continuation(ZFlow.input[E].flatMap(failure => ZFlow.fail(failure)), onSuccess)
  }

  def make(opEx: OperationExecutor[Any]): ZLayer[Clock with Has[DurableLog] with Has[KeyValueStore], Nothing, Has[ZFlowExecutor[String]]] =
    ((for {
      durableLog <- ZIO.service[DurableLog]
      kvStore <- ZIO.service[KeyValueStore]
      clock <- ZIO.service[Clock.Service]
      ref <- Ref.make[Map[String, Ref[PersistentExecutor.State[_, _]]]](Map.empty)
    } yield PersistentExecutor(clock, durableLog, kvStore, opEx, ref))).toLayer

  final case class State[E, A](
                                workflowId: String,
                                current: ZFlow[_, _, _],
                                tstate: TState,
                                stack: List[Continuation],
                                variables: Map[String, _],
                                result: DurablePromise[E, A],
                                envStack: List[SchemaAndValue[_]],
                                retry: Option[ZFlow[_, _, _]]
                              ) {

    def currentEnvironment: SchemaAndValue[_] = envStack.headOption.getOrElse(SchemaAndValue[Unit](Schema[Unit], ()))

    def pushEnv(schemaAndValue: SchemaAndValue[_]): State[E, A] = copy(envStack = schemaAndValue :: envStack)

    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): State[E, A] =
      copy(tstate = tstate.addCompensation(newCompensation))

    def addReadVar(value: Any): State[E, A] =
      copy(tstate = tstate.addReadVar(lookupName(value)))

    def addVariable(name: String, value: Any): State[E, A] = copy(variables = variables + (name -> value))

    def enterTransaction(flow: ZFlow[Any, _, _]): State[E, A] = copy(tstate = tstate.enterTransaction(flow))

    def getTransactionFlow: Option[ZFlow[Any, _, _]] = tstate match {
      case TState.Empty => None
      case TState.Transaction(flow, _, _) => Some(flow)
    }

    def getVariable(name: String): Option[_] = variables.get(name)

    //TODO scala map function
    private lazy val lookupName: Map[Any, String] =
      variables.map((t: (String, _)) => t._2 -> t._1).toMap
  }

  sealed trait TState {
    self =>
    def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): TState = self match {
      case TState.Empty => TState.Empty
      case TState.Transaction(flow, readVars, compensation) =>
        TState.Transaction(flow, readVars, newCompensation *> compensation)
      //TODO : Compensation Failure semantics
    }

    def addReadVar(name: String): TState = self match {
      case TState.Empty => TState.Empty
      case TState.Transaction(flow, readVars, compensation) => TState.Transaction(flow, readVars + name, compensation)
    }

    def allVariables: Set[String] = self match {
      case TState.Empty => Set()
      case TState.Transaction(_, readVars, _) => readVars
    }

    def enterTransaction(flow: ZFlow[Any, _, _]): TState =
      self match {
        case TState.Empty => TState.Transaction(flow, Set(), ZFlow.unit)
        case _ => self
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

}