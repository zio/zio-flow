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
import zio.flow.ExecutingFlow.InMemoryExecutingFlow
import zio.flow.Remote.{===>, RemoteFunction}
import zio.flow.serialization.{Deserializer, Serializer}
import zio.flow.{
  ActivityError,
  ExecutingFlow,
  ExecutionEnvironment,
  OperationExecutor,
  Remote,
  RemoteContext,
  RemoteVariableName,
  SchemaAndValue,
  SchemaOrNothing,
  ZFlow
}
import zio.schema._

import java.io.IOException

trait ZFlowExecutor[-U] {
  def submit[E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](uniqueId: U, flow: ZFlow[Any, E, A]): IO[E, A]
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

  final case class InMemory[U, R <: Clock: Tag](
    env: ZEnvironment[R],
    execEnv: ExecutionEnvironment,
    opExec: OperationExecutor[R],
    workflows: Ref[Map[U, Ref[InMemory.State]]]
  ) extends ZFlowExecutor[U] {

    import InMemory._
    import ZFlow._

    type Erased = ZFlow[Any, Any, Any]

    def erase(flow: ZFlow[_, _, _]): Erased = flow.asInstanceOf[Erased]

    val clock: Clock = env.get[Clock]

    def eval[A](r: Remote[A]): ZIO[RemoteContext, Nothing, SchemaAndValue[A]] =
      r.evalWithSchema.map(_.getOrElse(throw new IllegalStateException("Could not be reduced to a value.")))

    def lit[A](a: A): Remote[A] =
      Remote.Literal(a, SchemaOrNothing.fromSchema(Schema.fail("It is not expected to serialize this value")))

    def submit[E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](uniqueId: U, flow: ZFlow[Any, E, A]): IO[E, A] =
      //def compile[I, E, A](ref: Ref[State], input: I, flow: ZFlow[I, E, A]): ZIO[R, Nothing, Promise[E, A]] =
      for {
        ref     <- Ref.make(State(TState.Empty, Set.empty))
        acquire  = workflows.update(_ + ((uniqueId, ref)))
        release  = workflows.update(_ - uniqueId)
        promise <- Promise.make[E, A]
        _ <- acquire
               .acquireRelease(release)(
                 compile(promise, ref, (), flow).provideSomeLayer[R](RemoteContext.inMemory)
               )
               .provideEnvironment(env)
        result <- promise.await
      } yield result

    def compile[I, E, A](
      promise: Promise[E, A],
      ref: Ref[State],
      input: I,
      flow: ZFlow[I, E, A]
    ): ZIO[R with RemoteContext, Nothing, CompileStatus] =
      flow match {
        case Return(value) => eval(value).map(_.value).intoPromise(promise) as CompileStatus.Done

        case Now => clock.instant.exit.flatMap(e => promise.done(e)) as CompileStatus.Done

        case WaitTill(instant) =>
          (for {
            start <- clock.instant
            end   <- eval(instant).map(_.value)
            _     <- clock.sleep(Duration.between(start, end))
          } yield ()).intoPromise(promise.asInstanceOf[Promise[Nothing, Unit]]) as CompileStatus.Done

        case Modify(svar, f0) =>
          // TODO: implement with Remote.Variable instead of casting to Ref
          val f = f0.asInstanceOf[Any ===> (A, Any)]
          (for {
            variable <- eval(svar).map(_.value)
            optValue <- RemoteContext.getVariable[Any](variable.identifier)
            value <- ZIO
                       .fromOption(optValue)
                       .orElse(ZIO.dieMessage(s"Undefined variable ${variable.identifier} in Modify"))
            tuple             <- eval(f(lit(value))).map(_.value)
            (result, newValue) = tuple
            _                 <- RemoteContext.setVariable[Any](variable.identifier, newValue)
          } yield result).intoPromise(promise) as CompileStatus.Done

        case fold @ Fold(_, _, _) =>
          //TODO : Clean up required, why not 2 forkDaemons - try and try to reason about this
          for {
            innerPromise <- Promise.make[fold.ValueE, fold.ValueA]
            status       <- compile[fold.ValueR, fold.ValueE, fold.ValueA](innerPromise, ref, input, fold.value)
            status2 <- if (status == CompileStatus.Done)
                         innerPromise.await.foldZIO(
                           error =>
                             Promise
                               .make[fold.ValueE2, fold.ValueB]
                               .flatMap(p1 =>
                                 compile(
                                   p1,
                                   ref,
                                   input,
                                   ZFlow.unwrap(
                                     fold.ifError.asInstanceOf[
                                       RemoteFunction[fold.ValueE, ZFlow[fold.ValueR, fold.ValueE2, fold.ValueB]]
                                     ](lit(error))
                                   )(fold.errorSchema, fold.resultSchema)
                                 ) <* p1.await
                                   .intoPromise(promise)
                               ),
                           success =>
                             Promise
                               .make[fold.ValueE2, fold.ValueB]
                               .flatMap(p2 =>
                                 compile(
                                   p2,
                                   ref,
                                   input,
                                   ZFlow.unwrap(
                                     fold.ifSuccess.asInstanceOf[
                                       RemoteFunction[fold.ValueA, ZFlow[fold.ValueR, fold.ValueE2, fold.ValueB]]
                                     ](lit(success))
                                   )(fold.errorSchema, fold.resultSchema)
                                 ) <* p2.await
                                   .intoPromise(promise)
                                   .forkDaemon
                               )
                         )
                       else
                         innerPromise.await
                           .foldZIO(
                             error =>
                               Promise
                                 .make[fold.ValueE2, fold.ValueB]
                                 .flatMap(p1 =>
                                   compile(
                                     p1,
                                     ref,
                                     input,
                                     ZFlow.unwrap(
                                       fold.ifError.asInstanceOf[
                                         RemoteFunction[fold.ValueE, ZFlow[fold.ValueR, fold.ValueE2, fold.ValueB]]
                                       ](lit(error))
                                     )(fold.errorSchema, fold.resultSchema)
                                   ) <* p1.await
                                     .intoPromise(promise)
                                 ),
                             success =>
                               Promise
                                 .make[fold.ValueE2, fold.ValueB]
                                 .flatMap(p2 =>
                                   compile(
                                     p2,
                                     ref,
                                     input,
                                     ZFlow.unwrap(
                                       fold.ifSuccess.asInstanceOf[
                                         RemoteFunction[fold.ValueA, ZFlow[fold.ValueR, fold.ValueE2, fold.ValueB]]
                                       ](lit(success))
                                     )(fold.errorSchema, fold.resultSchema)
                                   ) <* p2.await
                                     .intoPromise(promise)
                                 )
                           )
                           .forkDaemon
                           .as(CompileStatus.Suspended)
          } yield status2

        case RunActivity(input, activity) =>
          (for {
            input  <- eval(input).map(_.value)
            output <- opExec.execute(input, activity.operation)
            _      <- ref.update(_.addCompensation(activity.compensate.unit.provide(lit(output))))
          } yield output).intoPromise(promise.asInstanceOf[Promise[ActivityError, A]]) as CompileStatus.Done

        case Transaction(flow) =>
          for {
            _      <- ref.update(_.enterTransaction(flow.provide(lit(input)), promise))
            status <- compile(promise, ref, input, flow.provide(lit(input)))
          } yield status

        case Input() => ZIO.succeed(input.asInstanceOf[A]).intoPromise(promise) as CompileStatus.Done

        case Ensuring(flow, finalizer) =>
          for {
            innerPromise <- Promise.make[E, A]
            flowStatus   <- compile(innerPromise, ref, input, flow)
            p1           <- Promise.make[Nothing, Any]
            rest = innerPromise.await.exit.flatMap { e =>
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
            evaluatedFlow <- eval(remote).map(_.value)
            status        <- compile(promise, ref, input, evaluatedFlow)
          } yield status

        case UnwrapRemote(remote) =>
          for {
            evaluatedRemote <- eval(remote).map(_.value)
            evaluated       <- eval(evaluatedRemote).map(_.value)
            _               <- promise.succeed(evaluated)
          } yield CompileStatus.Done

        case fork @ Fork(workflow) =>
          for {
            innerPromise <- Promise.make[fork.ValueE, fork.ValueA]
            fiber        <- compile(innerPromise, ref, input, workflow).fork
            _ <- promise
                   .asInstanceOf[Promise[Nothing, ExecutingFlow[fork.ValueE, fork.ValueA]]]
                   .succeed(InMemoryExecutingFlow(fiber))
          } yield CompileStatus.Done

        case timeout @ Timeout(flow, duration) =>
          val p = promise.asInstanceOf[Promise[E, Option[timeout.ValueA]]]
          for {
            innerPromise <- Promise.make[E, timeout.ValueA]
            duration     <- eval(duration).map(_.value)

            _ <- compile[I, E, timeout.ValueA](innerPromise, ref, input, flow)
            //TODO check other operations ensure done/to (promise) is called with await.
//            _      <- status
//                        .fold(p.succeed(None))(_ => innerPromise.await.timeout(duration).run.flatMap(e => p.done(e.map(Some(_)))))
//                        .forkDaemon
            _ <- innerPromise.await.timeout(duration).exit.flatMap(a => p.done(a))
          } yield CompileStatus.Done

        case provide @ Provide(_, _) =>
          for {
            innerPromise <- Promise.make[provide.ValueE, provide.ValueA]
            status <- eval(provide.value)
                        .map(_.value)
                        .flatMap(valueA =>
                          compile(innerPromise, ref, valueA, provide.flow)
                        ) //TODO : Input is provided in compile
            flowStatus <- if (status == CompileStatus.Done)
                            innerPromise.await.intoPromise(promise) as CompileStatus.Done
                          else innerPromise.await.intoPromise(promise).forkDaemon as CompileStatus.Suspended
          } yield flowStatus

        case Die => ZIO.die(new IllegalStateException("Could not evaluate ZFlow")) as CompileStatus.Done

        case RetryUntil =>
          for {
            state <- ref.get
            _ <- state.getTransactionFlow match {
                   case Some(FlowPromise(flow, promise)) =>
                     ZIO.environment[R with RemoteContext].flatMap { currentEnv =>
                       ref.update(
                         _.addRetry(
                           compile(promise, ref, (), flow).provideEnvironment(currentEnv)
                         )
                       )
                     }
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
            execflow <- eval(execFlow).map(_.value.asInstanceOf[InMemoryExecutingFlow[E, A]])
            _        <- execflow.fiber.join.intoPromise(promise).forkDaemon
          } yield CompileStatus.Done

        case Interrupt(execFlow) =>
          for {
            execflow <- eval(execFlow).map(_.value.asInstanceOf[InMemoryExecutingFlow[E, A]])
            _        <- execflow.fiber.interrupt.flatMap(e => promise.done(e)).forkDaemon
          } yield CompileStatus.Done

        case Fail(error) => eval(error).map(_.value).flatMap(promise.fail) as CompileStatus.Done

        case NewVar(name, initial) =>
          (for {
            value <- eval(initial)
            vref   = Remote.Variable(RemoteVariableName(name), SchemaOrNothing.fromSchema(value.schema))
            _     <- RemoteContext.setVariable(RemoteVariableName(name), value.value)
            _     <- ref.update(_.addVariable(name))
          } yield vref.asInstanceOf[A]).intoPromise(promise) as CompileStatus.Done

        case Log(message) =>
          ZIO
            .log(message)
            .intoPromise(promise.asInstanceOf[Promise[java.io.IOException, Unit]]) as CompileStatus.Done

        case iterate0 @ Iterate(_, _, _) =>
          val iterate = iterate0.asInstanceOf[Iterate[I, E, A]]

          val Iterate(initial, step, predicate) = iterate

          def loop(remoteA: Remote[A]): ZIO[R with RemoteContext, Nothing, CompileStatus] =
            Promise
              .make[E, A]
              .flatMap(p1 =>
                eval(predicate(remoteA)).map(_.value).flatMap { continue =>
                  if (continue)
                    for {
                      nextFlow <- eval(step(remoteA)).map(_.value)
                      status   <- compile[I, E, A](p1, ref, input, nextFlow)
                      status <-
                        if (status == CompileStatus.Done)
                          p1.await.exit
                            .flatMap(e =>
                              e.fold(cause => promise.failCause(cause) as CompileStatus.Done, a => loop(lit(a)))
                            )
                        else
                          p1.await.exit
                            .flatMap(e => e.fold(cause => promise.failCause(cause), a => loop(lit(a))))
                            .forkDaemon as CompileStatus.Suspended
                    } yield status
                  else
                    eval(remoteA).map(_.value).flatMap(a => promise.succeed(a) as CompileStatus.Done)
                }
              )
          loop(initial)

        case Apply(lambda) =>
          for {
            next   <- eval(lambda(lit(input))).map(_.value)
            result <- compile(promise, ref, (), next)
          } yield result

        case GetExecutionEnvironment =>
          promise.succeed(execEnv).as(CompileStatus.Done)

      }
  }

  object InMemory {

    sealed trait CompileStatus

    object CompileStatus {

      case object Done extends CompileStatus

      case object Suspended extends CompileStatus

    }

    def make[U, R <: Clock: Tag](
      env: ZEnvironment[R],
      opEx: OperationExecutor[R],
      serializer: Serializer,
      deserializer: Deserializer
    ): UIO[InMemory[U, R]] =
      (for {
        ref    <- Ref.make[Map[U, Ref[InMemory.State]]](Map.empty)
        execEnv = ExecutionEnvironment(serializer, deserializer)
      } yield InMemory[U, R](env, execEnv, opEx, ref))

    final case class State(
      tstate: TState,
      variables: Set[String],    // TODO : variable should be case class (TRef, TReEntrantLock), instead of Ref
      retry: UIO[Any] = ZIO.unit // TODO : Delete this, use zio STM's retry mechanism
      //TODO : Add a variable lock of type TReentrantLock
    ) {
      self =>

      def addCompensation(newCompensation: ZFlow[Any, ActivityError, Unit]): State =
        copy(tstate = tstate.addCompensation(newCompensation))

      def addReadVar(name: String): State =
        copy(tstate = tstate.addReadVar(name))

      def addRetry(retry: UIO[Any]): State = copy(retry = self.retry *> retry)

      def addVariable(name: String): State = copy(variables = variables + name)

      def enterTransaction[E, A](flow: ZFlow[Any, E, A], promise: Promise[E, A]): State =
        copy(tstate = tstate.enterTransaction(flow, promise))

      def getTransactionFlow: Option[FlowPromise[_, _]] = tstate match {
        case TState.Empty                          => None
        case TState.Transaction(flowPromise, _, _) => Some(flowPromise)
      }
    }

    sealed trait TState {
      self =>
      def addCompensation(newCompensation: ZFlow[Any, ActivityError, Unit]): TState = self match {
        case TState.Empty => TState.Empty
        case TState.Transaction(flowPromise, readVars, compensation) =>
          import zio.flow.schemaThrowable
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
        compensation: ZFlow[Any, ActivityError, Unit]
      ) extends TState

    }

    final case class FlowPromise[E, A](flow: ZFlow[Any, E, A], promise: Promise[E, A])

  }

}
