package zio.flow

import java.time.Duration

import zio._
import zio.clock.Clock

import zio.schema.Schema

trait ZFlowExecutor[-U] {
  def submit[E, A](uniqueId: U, flow: ZFlow[Any, E, A]): IO[E, A]
}

object ZFlowExecutor {

  /*
  orTry        : recover from `retryUntil` by trying a fallback workflow
  retryUntil   : go to start of transaction and wait until someone changes a variable
  transaction  : create a new scope that offers rollback on fail / retry, and which bounds retries

  State:
    Variables          = Map[String, Ref[Value]]
    Current            = The current ZFlow
    Continuation Stack = What to do AFTER producing the current ZFlow value
  TransactionalState:
    NonTransactionl |
    Transactional(parent: TransactionalState, undoLogStack: Stack[ZFlow], localReadVariables: Set[String])

  On every transaction, capture new transaction details:

  1. Create state snapshot
  2. Create an empty undo log stack
  3. Create an empty set of "read" variables

  As we are executing operations inside each transaction:

  1. For activities, push compensations on undo log stack
  2. For state changes, make the changes
  3. Track all variables read

  On uncaught failure inside ANY transaction:

  1. Run undo log for THIS transaction
  2. Reset state to THIS snapshot
  3. Continue processing failure (possibly rolling up!)

  On retry inside a transaction:

  1. Run undo log ALL THE WAY TO THE TOP
  2. Reset state to TOPMOST snapshot
  3. Wait on ANY variables read inside ANY transaction to change

   */
  final case class InMemory[U, R <: Clock](
    env: R,
    opExec: OperationExecutor[R],
    workflows: Ref[Map[U, Ref[InMemory.State]]]
  ) extends ZFlowExecutor[U] {

    import InMemory._
    import ZFlow._

    type Erased = ZFlow[Any, Any, Any]

    def erase(flow: ZFlow[_, _, _]): Erased = flow.asInstanceOf[Erased]

    val clock: Clock.Service = env.get[Clock.Service]

    def eval[A](r: Remote[A]): UIO[A] = UIO(
      r.eval.getOrElse(throw new IllegalStateException("Eval could not be reduced to Right of Either."))
    )

    def lit[A](a: A): Remote[A] =
      Remote.Literal(a, Schema.fail("It is not expected to serialize this value"))

    def getVariable(workflowId: U, variableName: String): UIO[Option[Any]] =
      (for {
        map      <- workflows.get
        stateRef <- ZIO.fromOption(map.get(workflowId))
        state    <- stateRef.get
        vRef     <- ZIO.fromOption(state.getVariable(variableName))
        v        <- vRef.get
      } yield v).optional

    def setVariable(workflowId: U, variableName: String, value: Any): UIO[Boolean] =
      (for {
        map      <- workflows.get
        stateRef <- ZIO.fromOption(map.get(workflowId))
        state    <- stateRef.get
        vRef     <- ZIO.fromOption(state.getVariable(variableName))
        _        <- vRef.set(value.asInstanceOf)
        _        <- stateRef.modify(state => (state.retry.forkDaemon, state.copy(retry = ZIO.unit))).flatten
      } yield true).catchAll(_ => UIO(false))

    def submit[E, A](uniqueId: U, flow: ZFlow[Any, E, A]): IO[E, A] = {
      //def compile[I, E, A](ref: Ref[State], input: I, flow: ZFlow[I, E, A]): ZIO[R, Nothing, Promise[E, A]] =
      def compile[I, E, A](ref: Ref[State], input: I, flow: ZFlow[I, E, A])(implicit schema: Schema[I]): ZIO[R, E, A] =
        flow match {
          case Return(value) => eval(value)

          case Now => clock.instant

          case WaitTill(instant) =>
            for {
              start <- clock.instant
              end   <- eval(instant)
              _     <- UIO(println(start))
              _     <- UIO(println(end))
              _     <- clock.sleep(Duration.between(start, end))
            } yield ()

          case Modify(svar, f0) =>
            val f = f0.asInstanceOf[Remote[Any] => Remote[(A, Any)]]

            for {
              vRef       <- eval(svar).map(_.asInstanceOf[Ref[Any]])
              value      <- vRef.get
              tuple      <- eval(f(lit(value)))
              (a, value2) = tuple
              _          <- vRef.set(value2)
              _          <- ref.update(_.addReadVar(vRef))
            } yield a

          case Fold(value, ifError, ifSuccess) =>
            compile(ref, input, value).foldM(
              error => compile(ref, input, ifError(lit(error))),
              success => compile(ref, input, ifSuccess(lit(success)))
            )

          case RunActivity(input, activity) =>
            for {
              input  <- eval(input)
              output <- opExec.execute(input, activity.operation)
              _      <- ref.update(_.addCompensation(activity.compensate.provide(lit(output))))
            } yield output

          case Transaction(flow) =>
            ref.update(_.enterTransaction(flow.provide(input))).flatMap(_ => compile(ref, input, flow))

          case Input(_) => ZIO.succeed(input.asInstanceOf[A])

          case Ensuring(flow, finalizer) =>
            compile(ref, input, flow).ensuring(compile(ref, input, finalizer))

          case Unwrap(remote) =>
            eval(remote).flatMap(compile(ref, input, _))

          case Foreach(values, body) =>
            eval(values).flatMap(list =>
              ZIO.foreach(list) { a =>
                compile(ref, input, body(lit(a)))
              }
            )

          case Fork(workflow) => compile(ref, input, workflow).fork.map(_.asInstanceOf[ExecutingFlow[Any, Any]])

          case Timeout(flow, duration) =>
            eval(duration).flatMap(duration => compile(ref, input, flow).timeout(duration))

          //TODO : Not sure
          case Provide(value, flow)    =>
            eval(value).flatMap(r => compile(ref, r, flow)(Schema.fail("Schema any should not be used.")))

          case Die => ZIO.die(new IllegalStateException("Could not evaluate ZFlow"))

          case RetryUntil =>
            for {
              promise <- Promise.make[E, A]
              state   <- ref.get
              _       <- state.getTransactionFlow match {
                           case Some(flow) =>
                             ref.update(
                               _.copy(retry =
                                 compile(ref, (), flow)
                                   .asInstanceOf[ZIO[R, E, A]]
                                   .run
                                   .flatMap(exit => promise.done(exit))
                                   .provide(env)
                               )
                             )
                           case None       => ZIO.unit
                         }
              a       <- promise.await
            } yield a

          case OrTry(left, right) => ???

          case Await(execFlow) =>
            eval(execFlow).flatMap(ef => ef.asInstanceOf[Fiber[E, A]].join.either)

          case Interrupt(execFlow) =>
            eval(execFlow).flatMap(ef => ef.asInstanceOf[Fiber[E, A]].interrupt.map(_.toEither))

          case Fail(error) => eval(error).flatMap(ZIO.fail(_))

          case NewVar(name, initial) =>
            for {
              value <- eval(initial)
              vref  <- Ref.make(value)
              _     <- ref.update(_.addVariable(name, vref))
            } yield vref.asInstanceOf[A]

          case iterate0 @ Iterate(_, _, _) =>
            val iterate = iterate0.asInstanceOf[Iterate[I, E, A]]

            val Iterate(self, step, predicate) = iterate

            def loop(a: A): ZIO[R, E, A] = {
              val remoteA: Remote[A] = lit(a)

              eval(predicate(remoteA)).flatMap { continue =>
                if (continue) compile(ref, input, step(remoteA)).flatMap(loop(_))
                else ZIO.succeed(a)
              }
            }

            compile(ref, input, self).flatMap(loop(_))
        }

      for {
        ref    <- Ref.make(State(TState.Empty, Map()))
        acquire = workflows.update(_ + ((uniqueId, ref)))
        release = workflows.update(_ - uniqueId)
        result <- acquire.bracket_(release)(compile(ref, (), flow).provide(env))
      } yield result
    }
  }

  object InMemory {

    def make[U, R <: Clock](env: R, opEx: OperationExecutor[R]): UIO[InMemory[U, R]] =
      (for {
        ref <- Ref.make[Map[U, Ref[InMemory.State]]](Map.empty)
      } yield InMemory[U, R](env, opEx, ref))

    final case class State(
      tstate: TState,
      variables: Map[String, Ref[_]],
      retry: UIO[Any] = ZIO.unit
    ) {

      def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): State =
        copy(tstate = tstate.addCompensation(newCompensation))

      def addReadVar(ref: Ref[_]): State =
        copy(tstate = tstate.addReadVar(lookupName(ref)))

      def addVariable(name: String, ref: Ref[_]): State = copy(variables = variables + (name -> ref))

      def enterTransaction(flow: ZFlow[Any, _, _]): State = copy(tstate = tstate.enterTransaction(flow))

      def getTransactionFlow: Option[ZFlow[Any, _, _]] = tstate match {
        case TState.Empty                   => None
        case TState.Transaction(flow, _, _) => Some(flow)
      }

      def getVariable(name: String): Option[Ref[_]] = variables.get(name)

      //TODO scala map function
      private lazy val lookupName: Map[Ref[_], String] = variables.map { case (l, r) =>
        (r, l)
      }.toMap
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

  }

}
