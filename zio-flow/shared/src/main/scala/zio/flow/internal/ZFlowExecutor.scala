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

import java.time.Duration

import zio._
import zio.clock.Clock
import zio.console.putStrLn
import zio.flow.Remote
import zio.flow.{ActivityError, ExecutingFlow, OperationExecutor, ZFlow}
import zio.schema.Schema

trait ZFlowExecutor[-U] {
  def submit[E: Schema, A: Schema](uniqueId: U, flow: ZFlow[Any, E, A]): IO[E, A]
}

object ZFlowExecutor {

  /*
  orTry        : recover from `retryUntil` by trying a fallback workflow
  retryUntil   : go to start of transaction and wait until someone changes a variable
  transaction  : create a new scope that offers rollback on fail / retry, and which bounds retries

  State:
    Variables          = Map[String, Ref[Value]]
    Current            = The current ZFlow
    Instruction Stack = What to do AFTER producing the current ZFlow value
  TransactionalState:
    NonTransactional |
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
      r.eval.getOrElse(throw new IllegalStateException("Could not be reduced to a value."))
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

    def submit[E: Schema, A: Schema](uniqueId: U, flow: ZFlow[Any, E, A]): IO[E, A] =
      //def compile[I, E, A](ref: Ref[State], input: I, flow: ZFlow[I, E, A]): ZIO[R, Nothing, Promise[E, A]] =
      for {
        ref     <- Ref.make(State(TState.Empty, Map()))
        acquire  = workflows.update(_ + ((uniqueId, ref)))
        release  = workflows.update(_ - uniqueId)
        promise <- Promise.make[E, A]
        _       <- acquire.bracket_(release)(compile(promise, ref, (), flow)).provide(env)
        result  <- promise.await
      } yield result
    def compile[I, E, A](
      promise: Promise[E, A],
      ref: Ref[State],
      input: I,
      flow: ZFlow[I, E, A]
    ): ZIO[R, Nothing, CompileStatus] =
      flow match {
        case Return(value) => eval(value).to(promise) as CompileStatus.Done

        case Now => clock.instant.run.flatMap(e => promise.done(e)) as CompileStatus.Done

        case WaitTill(instant) =>
          (for {
            start <- clock.instant
            end   <- eval(instant)
            _     <- clock.sleep(Duration.between(start, end))
          } yield ()).to(promise.asInstanceOf[Promise[Nothing, Unit]]) as CompileStatus.Done

        case Modify(svar, f0) =>
          val f = f0.asInstanceOf[Remote[Any] => Remote[(A, Any)]]
          (for {
            vRef       <- eval(svar).map(_.asInstanceOf[Ref[Any]])
            value      <- vRef.get
            tuple      <- eval(f(lit(value)))
            (a, value2) = tuple
            _          <- vRef.set(value2)
            _          <- ref.update(_.addReadVar(vRef))
          } yield a).to(promise) as CompileStatus.Done

        case fold @ Fold(_, _, _) =>
          //TODO : Clean up required, why not 2 forkDaemons - try and try to reason about this
          for {
            innerPromise <- Promise.make[fold.ValueE, fold.ValueA]
            status       <- compile[fold.ValueR, fold.ValueE, fold.ValueA](innerPromise, ref, input, fold.value)
            status2 <- if (status == CompileStatus.Done)
                         innerPromise.await.foldM(
                           error =>
                             Promise
                               .make[E, A]
                               .flatMap(p1 =>
                                 compile(p1, ref, input, fold.ifError.provide(lit(error))) <* p1.await.to(promise)
                               ),
                           success =>
                             Promise
                               .make[E, A]
                               .flatMap(p2 =>
                                 compile(p2, ref, input, fold.ifSuccess.provide(lit(success))) <* p2.await
                                   .to(promise)
                                   .forkDaemon
                               )
                         )
                       else
                         innerPromise.await
                           .foldM(
                             error =>
                               Promise
                                 .make[E, A]
                                 .flatMap(p1 =>
                                   compile(p1, ref, input, fold.ifError.provide(lit(error))) <* p1.await.to(promise)
                                 ),
                             success =>
                               Promise
                                 .make[E, A]
                                 .flatMap(p2 =>
                                   compile(p2, ref, input, fold.ifSuccess.provide(lit(success))) <* p2.await.to(promise)
                                 )
                           )
                           .forkDaemon
                           .as(CompileStatus.Suspended)
          } yield status2

        case RunActivity(input, activity) =>
          (for {
            input  <- eval(input)
            output <- opExec.execute(input, activity.operation)
            _      <- ref.update(_.addCompensation(activity.compensate.provide(lit(output))))
          } yield output).to(promise.asInstanceOf[Promise[ActivityError, A]]) as CompileStatus.Done

        case Transaction(flow) =>
          for {
            _      <- ref.update(_.enterTransaction(flow.provide(lit(input)), promise))
            status <- compile(promise, ref, input, flow.provide(lit(input)))
          } yield status

        case Input() => ZIO.succeed(input.asInstanceOf[A]).to(promise) as CompileStatus.Done

        case Ensuring(flow, finalizer) =>
          for {
            innerPromise <- Promise.make[E, A]
            flowStatus   <- compile(innerPromise, ref, input, flow)
            p1           <- Promise.make[Nothing, Any]
            rest = innerPromise.await.run.flatMap { e =>
                     compile(p1, ref, input, finalizer) <*
                       (p1.await *>
                         promise.done(e)).forkDaemon
                   }
            flowStatus <- if (flowStatus == CompileStatus.Done) rest
                          else
                            rest.forkDaemon as CompileStatus.Suspended
          } yield flowStatus

        case Unwrap(remote) =>
          for {
            evaluatedFlow <- eval(remote)
            status        <- compile(promise, ref, input, evaluatedFlow)
          } yield status

        case fork @ Fork(workflow) =>
          for {
            innerPromise <- Promise.make[fork.ValueE, fork.ValueA]
            fiber        <- compile(innerPromise, ref, input, workflow).fork
            _ <- promise
                   .asInstanceOf[Promise[Nothing, ExecutingFlow[fork.ValueE, fork.ValueA]]]
                   .succeed(fiber.asInstanceOf[ExecutingFlow[fork.ValueE, fork.ValueA]])
          } yield CompileStatus.Done

        case timeout @ Timeout(flow, duration) =>
          val p = promise.asInstanceOf[Promise[E, Option[timeout.ValueA]]]
          for {
            innerPromise <- Promise.make[E, timeout.ValueA]
            duration     <- eval(duration)

            _ <- compile[I, E, timeout.ValueA](innerPromise, ref, input, flow)
            //TODO check other operations ensure done/to (promise) is called with await.
//            _      <- status
//                        .fold(p.succeed(None))(_ => innerPromise.await.timeout(duration).run.flatMap(e => p.done(e.map(Some(_)))))
//                        .forkDaemon
            _ <- innerPromise.await.timeout(duration).run.flatMap(a => p.done(a))
          } yield CompileStatus.Done

        case provide @ Provide(_, _) =>
          for {
            innerPromise <- Promise.make[provide.ValueE, provide.ValueA]
            status <- eval(provide.value).flatMap(valueA =>
                        compile(innerPromise, ref, valueA, provide.flow)
                      ) //TODO : Input is provided in compile
            flowStatus <- if (status == CompileStatus.Done) innerPromise.await.to(promise) as CompileStatus.Done
                          else innerPromise.await.to(promise).forkDaemon as CompileStatus.Suspended
          } yield flowStatus

        case Die => ZIO.die(new IllegalStateException("Could not evaluate ZFlow")) as CompileStatus.Done

        case RetryUntil =>
          for {
            state <- ref.get
            _ <- state.getTransactionFlow match {
                   case Some(FlowPromise(flow, promise)) =>
                     ref.update(_.addRetry(compile(promise, ref, (), flow).provide(env)))
                   case None => ZIO.dieMessage("There is no transaction to retry.")
                 }
          } yield CompileStatus.Suspended

        case OrTry(left, right) =>
          for {
            leftStatus <- compile(promise, ref, input, left)
            status <- if (leftStatus == CompileStatus.Suspended) {
                        compile(promise, ref, input, right)
                      } else {
                        ZIO.succeed(CompileStatus.Done)
                      }
          } yield status

        case Await(execFlow) =>
          for {
            execflow <- eval(execFlow).map(_.asInstanceOf[Fiber[E, A]])
            _        <- execflow.join.to(promise).forkDaemon
          } yield CompileStatus.Done

        case Interrupt(execFlow) =>
          for {
            execflow <- eval(execFlow).map(_.asInstanceOf[Fiber[E, A]])
            _        <- execflow.interrupt.flatMap(e => promise.done(e)).forkDaemon
          } yield CompileStatus.Done

        case Fail(error) => eval(error).flatMap(promise.fail) as CompileStatus.Done

        case NewVar(name, initial) =>
          (for {
            value <- eval(initial)
            vref  <- Ref.make(value)
            _     <- ref.update(_.addVariable(name, vref))
          } yield vref.asInstanceOf[A]).to(promise) as CompileStatus.Done

        case Log(message) =>
          putStrLn(message)
            .provideLayer(zio.console.Console.live)
            .to(promise.asInstanceOf[Promise[java.io.IOException, Unit]]) as CompileStatus.Done

        case iterate0 @ Iterate(_, _, _) =>
          val iterate = iterate0.asInstanceOf[Iterate[I, E, A]]

          val Iterate(initial, step, predicate) = iterate

          def loop(remoteA: Remote[A]): ZIO[R, Nothing, CompileStatus] =
            Promise
              .make[E, A]
              .flatMap(p1 =>
                eval(predicate(remoteA)).flatMap { continue =>
                  if (continue)
                    for {
                      status <- compile(p1, ref, input, step(remoteA))
                      status <-
                        if (status == CompileStatus.Done)
                          p1.await.run
                            .flatMap(e => e.fold(cause => promise.halt(cause) as CompileStatus.Done, a => loop(lit(a))))
                        else
                          p1.await.run
                            .flatMap(e => e.fold(cause => promise.halt(cause), a => loop(lit(a))))
                            .forkDaemon as CompileStatus.Suspended
                    } yield status
                  else
                    eval(remoteA).flatMap(a => promise.succeed(a) as CompileStatus.Done)
                }
              )
          loop(initial)

        case Apply(lambda) =>
          compile(promise, ref, (), lambda(lit(input)))
      }
  }

  object InMemory {

    sealed trait CompileStatus

    object CompileStatus {

      case object Done extends CompileStatus

      case object Suspended extends CompileStatus

    }

    def make[U, R <: Clock](env: R, opEx: OperationExecutor[R]): UIO[InMemory[U, R]] =
      (for {
        ref <- Ref.make[Map[U, Ref[InMemory.State]]](Map.empty)
      } yield InMemory[U, R](env, opEx, ref))

    final case class State(
      tstate: TState,
      variables: Map[String, Ref[_]], // TODO : variable should be case class (TRef, TReEntrantLock), instead of Ref
      retry: UIO[Any] = ZIO.unit      // TODO : Delete this, use zio STM's retry mechanism
      //TODO : Add a variable lock of type TReentrantLock
    ) {
      self =>

      def addCompensation(newCompensation: ZFlow[Any, ActivityError, Any]): State =
        copy(tstate = tstate.addCompensation(newCompensation))

      def addReadVar(ref: Ref[_]): State =
        copy(tstate = tstate.addReadVar(lookupName(ref)))

      def addRetry(retry: UIO[Any]): State = copy(retry = self.retry *> retry)

      def addVariable(name: String, ref: Ref[_]): State = copy(variables = variables + (name -> ref))

      def enterTransaction[E, A](flow: ZFlow[Any, E, A], promise: Promise[E, A]): State =
        copy(tstate = tstate.enterTransaction(flow, promise))

      def getTransactionFlow: Option[FlowPromise[_, _]] = tstate match {
        case TState.Empty                          => None
        case TState.Transaction(flowPromise, _, _) => Some(flowPromise)
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
        case TState.Empty => TState.Empty
        case TState.Transaction(flowPromise, readVars, compensation) =>
          TState.Transaction(flowPromise, readVars, newCompensation *> compensation)
        //TODO : Compensation Failure semantics
      }

      def addReadVar(name: String): TState = self match {
        case TState.Empty => TState.Empty
        case TState.Transaction(flowPromise, readVars, compensation) =>
          TState.Transaction(flowPromise, readVars + name, compensation)
      }

      def allVariables: Set[String] = self match {
        case TState.Empty                       => Set()
        case TState.Transaction(_, readVars, _) => readVars
      }

      def enterTransaction[E, A](flow: ZFlow[Any, E, A], promise: Promise[E, A]): TState =
        self match {
          case TState.Empty => TState.Transaction(FlowPromise(flow, promise), Set(), ZFlow.unit)
          case _            => self
        }
    }

    object TState {

      case object Empty extends TState

      final case class Transaction(
        flowPromise: FlowPromise[_, _],
        readVars: Set[String],
        compensation: ZFlow[Any, ActivityError, Any]
      ) extends TState

    }

    final case class FlowPromise[E, A](flow: ZFlow[Any, E, A], promise: Promise[E, A])

  }

}
