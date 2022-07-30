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

package zio.flow

import zio.ZNothing
import zio.flow.Remote._
import zio.schema.{CaseSet, Schema}

import java.time.{Duration, Instant}

/**
 * ZFlow is a serializable executable workflow.
 *
 * Values (including functions) used in ZFlows must be [[Remote]] values which
 * guarantees that they can be persisted.
 *
 * @tparam R
 *   The type of the input that can be provided for the workflow
 * @tparam E
 *   The type this workflow can fail with
 * @tparam A
 *   The result type this workflow returns with in case it succeeds
 */
sealed trait ZFlow[-R, +E, +A] {
  self =>

  /**
   * Executes this flow and then that flow, keeping only the result of the
   * second.
   *
   * An alias for [[zipRight]].
   */
  final def *>[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    zipRight(that)

  /**
   * Executes this flow and then that flow, keeping only the result of the
   * first.
   *
   * An alias for [[zipLeft]]
   */
  final def <*[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, A1] =
    zipLeft(that)

  /**
   * Executes this flow and then that flow keeping the result of both zipped
   * together
   *
   * An alias for [[zip]]
   */
  final def <*>[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, (A1, B)] =
    zip(that)

  /** Replace the flow's result value with the provided value */
  final def as[B](b: => Remote[B]): ZFlow[R, E, B] =
    self.map[B](_ => b)

  /** Recover from any failure of this flow by executing the given function */
  final def catchAll[R1 <: R, E1 >: E, A1 >: A, E2](
    f: Remote[E1] => ZFlow[R1, E2, A1]
  ): ZFlow[R1, E2, A1] =
    self.foldFlow(f, (a: Remote[A1]) => ZFlow(a))

  /**
   * Ensure that the given flow is executed after this flow, regardless of
   * success or failure
   */
  final def ensuring[E1 >: E, A1 >: A](
    flow: ZFlow[Any, ZNothing, Any]
  ): ZFlow[R, E1, A1] =
    ZFlow.Ensuring[R, E1, A1](self, flow.unit)

  /**
   * Executes this flow and then calls the given function with the flow's result
   * to determine the next steps.
   */
  final def flatMap[R1 <: R, E1 >: E, B](
    f: Remote[A] => ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    foldFlow(
      (e: Remote[E1]) => ZFlow.Fail(e),
      f
    )

  /**
   * Executes this flow and then calls either the onError or the onSuccess
   * functions to determine the next steps.
   */
  final def foldFlow[R1 <: R, E2, B](
    onError: Remote[E] => ZFlow[R1, E2, B],
    onSuccess: Remote[A] => ZFlow[R1, E2, B]
  ): ZFlow[R1, E2, B] =
    ZFlow.Fold(
      self,
      UnboundRemoteFunction.make(onError.andThen(_.toRemote)),
      UnboundRemoteFunction.make(onSuccess.andThen(_.toRemote))
    )

  /**
   * Execute this flow in the background.
   *
   * The returned value (of type [[ExecutingFlow]]) can be used to await or
   * interrupt the running flow.
   */
  final def fork: ZFlow[R, ZNothing, ExecutingFlow[E, A]] =
    ZFlow.Fork(self)

  /**
   * Executes this flow and based on its boolean result determines the next
   * steps by either calling ifTrue or ifFalse
   */
  final def ifThenElse[R1 <: R, E1 >: E, B](
    ifTrue: ZFlow[R1, E1, B],
    ifFalse: ZFlow[R1, E1, B]
  )(implicit
    ev: A <:< Boolean
  ): ZFlow[R1, E1, B] =
    self
      .widen[Boolean]
      .flatMap((bool: Remote[Boolean]) => ZFlow.unwrap(bool.ifThenElse(ifTrue.toRemote, ifFalse.toRemote)))

  /**
   * Repeatedly executes a flow until a given predicate becomes true.
   *
   * The initial value is the result of this flow. The step function is called
   * with this value to produce the next flow to execute. The iteration stops
   * when te predicate function returns true for the last step's result.
   */
  final def iterate[R1 <: R, E1 >: E, A1 >: A](step: Remote[A1] => ZFlow[R1, E1, A1])(
    predicate: Remote[A1] => Remote[Boolean]
  ): ZFlow[R1, E1, A1] =
    self.flatMap((remoteA: Remote[A1]) =>
      ZFlow.Iterate(
        remoteA,
        UnboundRemoteFunction.make((a1: Remote[A1]) => step(a1).toRemote),
        UnboundRemoteFunction.make(predicate)
      )
    )

  /** Maps the result of this flow with the given function */
  final def map[B](f: Remote[A] => Remote[B]): ZFlow[R, E, B] =
    self.flatMap[R, E, B](a => ZFlow(f(a)))

  /** Converts this flow's failure to a fatal flow failure */
  final def orDie[R1 <: R, E1 >: E, A1 >: A]: ZFlow[R1, ZNothing, A1] =
    self.catchAll[R1, E1, A1, ZNothing]((_: Remote[E1]) => ZFlow.Die)

  /**
   * Executes this flow and if it fails executes that flow.
   *
   * The flow and the fallback flow must have compatible result types. If they
   * don't, use [[orElseEither]].
   */
  final def orElse[R1 <: R, E1 >: E, E2, A1 >: A](
    that: ZFlow[R1, E2, A1]
  ): ZFlow[R1, E2, A1] =
    self.catchAll((_: Remote[E1]) => that)

  /**
   * Executes this flow and if it fails executes that flow. The two flows can
   * have different result types which will be captured in an Either value.
   */
  final def orElseEither[R1 <: R, A1 >: A, E2, B](
    that: ZFlow[R1, E2, B]
  ): ZFlow[R1, E2, Either[A1, B]] =
    self
      .map[Either[A1, B]](a => Remote.sequenceEither[A1, B](Left(a)))
      .catchAll((_: Remote[E]) => that.map((b: Remote[B]) => Remote.sequenceEither[A1, B](Right(b))))

  /**
   * Attempts to execute this flow, but then, if this flow is suspended due to
   * performing a retry operation inside a transaction (because conditions
   * necessary for executing this flow are not yet ready), then will switch over
   * to the specified flow.
   *
   * If this flow never suspends, then it will always execute to success or
   * failure, and the specified flow will never be executed.
   */
  final def orTry[R1 <: R, E1 >: E, A1 >: A](that: ZFlow[R1, E1, A1]): ZFlow[R1, E1, A1] =
    ZFlow.OrTry(self, that)

  /** Provide a value as this flow's input */
  final def provide(value: Remote[R]): ZFlow[Any, E, A] = ZFlow.Provide(value, self)

  /**
   * Try to execute this flow but timeout after the given duration.
   *
   * If the flow finished running within the time limits, the result is wrapped
   * in Some, otherwise if it timed out the result is None.
   */
  final def timeout(
    duration: Remote[Duration]
  ): ZFlow[R, E, Option[A]] =
    ZFlow.Timeout[R, E, A](self, duration)

  /** Ignores the successful result of this flow and return with unit instead */
  final def unit: ZFlow[R, E, Unit] = as(())

  /**
   * Executes this flow and then that flow keeping the result of both zipped
   * together
   *
   * Has a symbolic alias [[<*>]]
   */
  final def zip[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, (A1, B)] =
    self.flatMap((a: Remote[A1]) => that.map((b: Remote[B]) => a -> b))

  /**
   * Executes this flow and then that flow, keeping only the result of the
   * first.
   *
   * Has a symbolic alias [[<*]]
   */
  final def zipLeft[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, A1] =
    self.zip[R1, E1, A1, B](that).map((a: Remote[(A1, B)]) => a._1)

  /**
   * Executes this flow and then that flow, keeping only the result of the
   * second.
   *
   * Has a symbolic alias [[*>]].
   */
  final def zipRight[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    self.zip[R1, E1, A1, B](that).map((a: Remote[(A1, B)]) => a._2)

  /** Widen the result type of the flow */
  final def widen[A0](implicit ev: A <:< A0): ZFlow[R, E, A0] = {
    val _ = ev

    self.asInstanceOf[ZFlow[R, E, A0]]
  }

  private[flow] final def substitute[B](f: Remote.Substitutions): ZFlow[R, E, A] =
    if (f.cut(variableUsage)) this else substituteRec(f)

  protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A]

  private[flow] val variableUsage: VariableUsage
}

object ZFlow {

  final case class Await[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Either[E, A]] {
    type ValueE = E
    type ValueA = A

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, ActivityError, Either[E, A]] =
      Await(exFlow.substitute(f))

    override private[flow] val variableUsage = exFlow.variableUsage
  }

  object Await {
    def schema[E, A]: Schema[Await[E, A]] =
      Schema.CaseClass1[Remote[ExecutingFlow[E, A]], Await[E, A]](
        Schema.Field("exFlow", Remote.schema[ExecutingFlow[E, A]]),
        { case (exFlow) =>
          Await(exFlow)
        },
        _.exFlow
      )

    def schemaCase[R, E, A]: Schema.Case[Await[E, A], ZFlow[R, E, A]] =
      Schema.Case("Await", schema[E, A], _.asInstanceOf[Await[E, A]])
  }

  case object Die extends ZFlow[Any, Nothing, Nothing] {

    val schema: Schema[Die.type] = Schema.singleton(Die)

    def schemaCase[R, E, A]: Schema.Case[Die.type, ZFlow[R, E, A]] =
      Schema.Case("Die", schema, _.asInstanceOf[Die.type])

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Nothing] =
      Die

    override private[flow] val variableUsage = VariableUsage.none
  }

  final case class Ensuring[R, E, A](flow: ZFlow[R, E, A], finalizer: ZFlow[Any, ZNothing, Unit])
      extends ZFlow[R, E, A] {
    type ValueE = E
    type ValueA = A

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
      Ensuring(flow.substitute(f), finalizer.substitute(f))

    override private[flow] val variableUsage = flow.variableUsage.union(finalizer.variableUsage)
  }

  object Ensuring {
    def schema[R, E, A]: Schema[Ensuring[R, E, A]] =
      Schema.defer(
        Schema.CaseClass2[ZFlow[R, E, A], ZFlow[Any, Nothing, Unit], Ensuring[R, E, A]](
          Schema.Field("flow", ZFlow.schema[R, E, A]),
          Schema.Field("finalizer", ZFlow.schema[Any, Nothing, Unit]),
          { case (flow, finalizer) => Ensuring(flow, finalizer) },
          _.flow,
          _.finalizer
        )
      )

    def schemaCase[R, E, A]: Schema.Case[Ensuring[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Ensuring", schema[R, E, A], _.asInstanceOf[Ensuring[R, E, A]])
  }

  final case class Fail[E](error: Remote[E]) extends ZFlow[Any, E, Nothing] {

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, E, Nothing] =
      Fail(error.substitute(f))

    override private[flow] val variableUsage = error.variableUsage
  }

  object Fail {
    def schema[E]: Schema[Fail[E]] =
      Remote
        .schema[E]
        .transform(
          Fail(_),
          _.error
        )

    def schemaCase[R, E, A]: Schema.Case[Fail[E], ZFlow[R, E, A]] =
      Schema.Case("Fail", schema[E], _.asInstanceOf[Fail[E]])
  }

  final case class Fold[R, E, E2, A, B](
    value: ZFlow[R, E, A],
    ifError: UnboundRemoteFunction[E, ZFlow[R, E2, B]],
    ifSuccess: UnboundRemoteFunction[A, ZFlow[R, E2, B]]
  ) extends ZFlow[R, E2, B] {
    type ValueE  = E
    type ValueE2 = E2
    type ValueA  = A
    type ValueR  = R
    type ValueB  = B

    override protected def substituteRec[C](f: Remote.Substitutions): ZFlow[R, E2, B] =
      Fold(
        value.substitute(f),
        ifError.substitute(f).asInstanceOf[UnboundRemoteFunction[E, ZFlow[R, E2, B]]],
        ifSuccess.substitute(f).asInstanceOf[UnboundRemoteFunction[A, ZFlow[R, E2, B]]]
      )

    override private[flow] val variableUsage =
      value.variableUsage.union(ifError.variableUsage).union(ifSuccess.variableUsage)
  }

  object Fold {
    def schema[R, E, E2, A, B]: Schema[Fold[R, E, E2, A, B]] =
      Schema.defer(
        Schema.CaseClass3[ZFlow[R, E, A], UnboundRemoteFunction[E, ZFlow[R, E2, B]], UnboundRemoteFunction[
          A,
          ZFlow[R, E2, B]
        ], Fold[R, E, E2, A, B]](
          Schema.Field("value", ZFlow.schema[R, E, A]),
          Schema.Field("ifError", UnboundRemoteFunction.schema[E, ZFlow[R, E2, B]]),
          Schema.Field("ifSuccess", UnboundRemoteFunction.schema[A, ZFlow[R, E2, B]]),
          (value, ifError, ifSuccess) => Fold(value, ifError, ifSuccess),
          _.value,
          _.ifError,
          _.ifSuccess
        )
      )

    def schemaCase[R, E, A]: Schema.Case[ZFlow.Fold[Any, Any, Any, Any, Any], ZFlow[R, E, A]] =
      Schema.Case("Fold", schema[Any, Any, Any, Any, Any], _.asInstanceOf[Fold[Any, Any, Any, Any, Any]])
  }

  final case class Fork[R, E, A](flow: ZFlow[R, E, A]) extends ZFlow[R, Nothing, ExecutingFlow[E, A]] {
    type ValueE = E
    type ValueA = A

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, Nothing, ExecutingFlow[E, A]] =
      Fork(flow.substitute(f))

    override private[flow] val variableUsage = flow.variableUsage
  }

  object Fork {
    def schema[R, E, A]: Schema[Fork[R, E, A]] =
      Schema.defer(
        ZFlow
          .schema[R, E, A]
          .transform(
            Fork(_),
            _.flow
          )
      )

    def schemaCase[R, E, A]: Schema.Case[Fork[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Fork", schema[R, E, A], _.asInstanceOf[Fork[R, E, A]])
  }

  final case class Input[R]() extends ZFlow[R, Nothing, R] {

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, Nothing, R] =
      this

    override private[flow] val variableUsage = VariableUsage.none
  }

  object Input {
    def schema[R]: Schema[Input[R]] =
      Schema[Unit].transform(
        _ => Input(),
        _ => ()
      )

    def schemaCase[R, E, A]: Schema.Case[Input[R], ZFlow[R, E, A]] =
      Schema.Case("Input", schema[R], _.asInstanceOf[Input[R]])
  }

  final case class Interrupt[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Unit] {

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, ActivityError, Unit] =
      Interrupt(exFlow.substitute(f))

    override private[flow] val variableUsage = exFlow.variableUsage
  }

  object Interrupt {
    def schema[E, A]: Schema[Interrupt[E, A]] =
      Remote
        .schema[ExecutingFlow[E, A]]
        .transform(
          exFlow => Interrupt(exFlow),
          _.exFlow
        )

    def schemaCase[R, E, A]: Schema.Case[Interrupt[E, A], ZFlow[R, E, A]] =
      Schema.Case("Interrupt", schema[E, A], _.asInstanceOf[Interrupt[E, A]])
  }

  final case class Iterate[R, E, A](
    initial: Remote[A],
    step: UnboundRemoteFunction[A, ZFlow[R, E, A]],
    predicate: UnboundRemoteFunction[A, Boolean]
  ) extends ZFlow[R, E, A] {
    type ValueE = E
    type ValueA = A

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
      Iterate(
        initial.substitute(f),
        step.substitute(f).asInstanceOf[UnboundRemoteFunction[A, ZFlow[R, E, A]]],
        predicate.substitute(f).asInstanceOf[UnboundRemoteFunction[A, Boolean]]
      )

    override private[flow] val variableUsage =
      initial.variableUsage.union(step.variableUsage).union(predicate.variableUsage)
  }

  object Iterate {
    def schema[R, E, A]: Schema[Iterate[R, E, A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[A], UnboundRemoteFunction[A, ZFlow[R, E, A]], UnboundRemoteFunction[
          A,
          Boolean
        ], Iterate[R, E, A]](
          Schema.Field("initial", Remote.schema[A]),
          Schema.Field("step", UnboundRemoteFunction.schema[A, ZFlow[R, E, A]]),
          Schema.Field("predicate", UnboundRemoteFunction.schema[A, Boolean]),
          { case (initial, step, predicate) =>
            Iterate(initial, step, predicate)
          },
          _.initial,
          _.step,
          _.predicate
        )
      )

    def schemaCase[R, E, A]: Schema.Case[Iterate[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Iterate", schema[R, E, A], _.asInstanceOf[Iterate[R, E, A]])
  }

  final case class Log(message: Remote[String]) extends ZFlow[Any, Nothing, Unit] {

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Unit] =
      Log(message.substitute(f))

    override private[flow] val variableUsage = message.variableUsage
  }

  object Log {
    val schema: Schema[Log] =
      Schema.defer {
        Remote.schema[String].transform(Log(_), _.message)
      }

    def schemaCase[R, E, A]: Schema.Case[ZFlow.Log, ZFlow[R, E, A]] =
      Schema.Case("Log", schema, _.asInstanceOf[Log])
  }

  final case class Modify[A, B](svar: Remote[RemoteVariableReference[A]], f: UnboundRemoteFunction[A, (B, A)])
      extends ZFlow[Any, Nothing, B] {
    override protected def substituteRec[C](fn: Remote.Substitutions): ZFlow[Any, Nothing, B] =
      Modify(
        svar.substitute(fn),
        f.substitute(fn).asInstanceOf[UnboundRemoteFunction[A, (B, A)]]
      )

    override private[flow] val variableUsage = svar.variableUsage.union(f.variableUsage)
  }

  object Modify {
    def schema[A, B]: Schema[Modify[A, B]] =
      Schema.CaseClass2[Remote[RemoteVariableReference[A]], UnboundRemoteFunction[A, (B, A)], Modify[
        A,
        B
      ]](
        Schema.Field("svar", Remote.schema[RemoteVariableReference[A]]),
        Schema.Field("f", UnboundRemoteFunction.schema[A, (B, A)]),
        { case (svar, f) =>
          Modify(svar, f)
        },
        _.svar,
        _.f
      )

    def schemaCase[R, E, A]: Schema.Case[Modify[Any, Any], ZFlow[R, E, A]] =
      Schema.Case("Modify", schema[Any, Any], _.asInstanceOf[Modify[Any, Any]])
  }

  final case class NewVar[A](name: String, initial: Remote[A]) extends ZFlow[Any, Nothing, RemoteVariableReference[A]] {

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, RemoteVariableReference[A]] =
      NewVar(name, initial.substitute(f))

    override private[flow] val variableUsage = initial.variableUsage
  }

  object NewVar {
    def schema[A]: Schema[NewVar[A]] =
      Schema.CaseClass2[String, Remote[A], NewVar[A]](
        Schema.Field("name", Schema[String]),
        Schema.Field("initial", Remote.schema[A]),
        { case (name, initial) => NewVar(name, initial) },
        _.name,
        _.initial
      )

    def schemaCase[R, E, A]: Schema.Case[NewVar[A], ZFlow[R, E, A]] =
      Schema.Case("NewVar", schema[A], _.asInstanceOf[NewVar[A]])
  }

  case object Now extends ZFlow[Any, Nothing, Instant] {
    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Instant] =
      Now

    val schema: Schema[Now.type] = Schema.singleton(Now)

    def schemaCase[R, E, A]: Schema.Case[Now.type, ZFlow[R, E, A]] =
      Schema.Case("Now", schema, _.asInstanceOf[Now.type])

    override private[flow] val variableUsage = VariableUsage.none
  }

  final case class OrTry[R, E, A](left: ZFlow[R, E, A], right: ZFlow[R, E, A]) extends ZFlow[R, E, A] {

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
      OrTry(left.substitute(f), right.substitute(f))

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object OrTry {
    def schema[R, E, A]: Schema[OrTry[R, E, A]] =
      Schema.defer(
        Schema.CaseClass2[ZFlow[R, E, A], ZFlow[R, E, A], OrTry[R, E, A]](
          Schema.Field("left", ZFlow.schema[R, E, A]),
          Schema.Field("right", ZFlow.schema[R, E, A]),
          { case (left, right) => OrTry(left, right) },
          _.left,
          _.right
        )
      )

    def schemaCase[R, E, A]: Schema.Case[OrTry[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("OrTry", schema[R, E, A], _.asInstanceOf[OrTry[R, E, A]])
  }

  final case class Provide[R, E, A](value: Remote[R], flow: ZFlow[R, E, A]) extends ZFlow[Any, E, A] {
    type ValueE = E
    type ValueA = A

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, E, A] =
      Provide(value.substitute(f), flow.substitute(f))

    override private[flow] val variableUsage = value.variableUsage.union(flow.variableUsage)
  }

  object Provide {
    def schema[R, E, A]: Schema[Provide[R, E, A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[R], ZFlow[R, E, A], Provide[R, E, A]](
          Schema.Field("value", Remote.schema[R]),
          Schema.Field("flow", ZFlow.schema[R, E, A]),
          { case (value, flow) => Provide(value, flow) },
          _.value,
          _.flow
        )
      )

    def schemaCase[R, E, A]: Schema.Case[Provide[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Provide", schema[R, E, A], _.asInstanceOf[Provide[R, E, A]])
  }

  final case class Read[A](svar: Remote[RemoteVariableReference[A]]) extends ZFlow[Any, Nothing, A] {
    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, A] =
      Read(svar.substitute(f))

    override private[flow] val variableUsage = svar.variableUsage
  }

  object Read {
    def schema[A]: Schema[Read[A]] =
      Schema.CaseClass1[Remote[RemoteVariableReference[A]], Read[A]](
        Schema.Field("svar", Remote.schema[RemoteVariableReference[A]]),
        { case (svar) => Read(svar) },
        _.svar
      )

    def schemaCase[R, E, A]: Schema.Case[Read[A], ZFlow[R, E, A]] =
      Schema.Case("Read", schema[A], _.asInstanceOf[Read[A]])
  }

  case object RetryUntil extends ZFlow[Any, Nothing, Nothing] {

    val schema: Schema[RetryUntil.type] = Schema.singleton(RetryUntil)

    def schemaCase[R, E, A]: Schema.Case[RetryUntil.type, ZFlow[R, E, A]] =
      Schema.Case("RetryUntil", schema, _.asInstanceOf[RetryUntil.type])

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Nothing] =
      RetryUntil

    override private[flow] val variableUsage = VariableUsage.none
  }

  final case class Return[A](value: Remote[A]) extends ZFlow[Any, Nothing, A] {
    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, A] =
      Return(value.substitute(f))

    override private[flow] val variableUsage = value.variableUsage
  }

  object Return {
    def schema[A]: Schema[Return[A]] =
      Remote
        .schema[A]
        .transform(
          Return(_),
          _.value
        )

    def schemaCase[R, E, A]: Schema.Case[Return[A], ZFlow[R, E, A]] =
      Schema.Case("Return", schema[A], _.asInstanceOf[Return[A]])
  }

  final case class RunActivity[R, A](input: Remote[R], activity: Activity[R, A]) extends ZFlow[Any, ActivityError, A] {

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, ActivityError, A] =
      RunActivity(input.substitute(f), activity)

    override private[flow] val variableUsage = input.variableUsage
  }

  object RunActivity {
    def schema[R, A]: Schema[RunActivity[R, A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[R], Activity[R, A], RunActivity[R, A]](
          Schema.Field("input", Remote.schema[R]),
          Schema.Field("activity", Activity.schema[R, A]),
          { case (input, activity) => RunActivity(input, activity) },
          _.input,
          _.activity
        )
      )

    def schemaCase[R, E, A]: Schema.Case[RunActivity[Any, A], ZFlow[R, E, A]] =
      Schema.Case("RunActivity", schema[Any, A], _.asInstanceOf[RunActivity[Any, A]])
  }

  final case class Timeout[R, E, A](flow: ZFlow[R, E, A], duration: Remote[Duration]) extends ZFlow[R, E, Option[A]] {
    type ValueA = A
    type ValueE = E

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, Option[A]] =
      Timeout(flow.substitute(f), duration.substitute(f))

    override private[flow] val variableUsage = flow.variableUsage.union(duration.variableUsage)
  }

  object Timeout {
    def schema[R, E, A]: Schema[Timeout[R, E, A]] =
      Schema.defer(
        Schema.CaseClass2[ZFlow[R, E, A], Remote[Duration], Timeout[R, E, A]](
          Schema.Field("flow", ZFlow.schema[R, E, A]),
          Schema.Field("duration", Remote.schema[Duration]),
          { case (workflow, duration) =>
            Timeout(workflow, duration)
          },
          _.flow,
          _.duration
        )
      )

    def schemaCase[R, E, A]: Schema.Case[Timeout[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Timeout", schema[R, E, A], _.asInstanceOf[Timeout[R, E, A]])
  }

  final case class Transaction[R, E, A](workflow: ZFlow[R, E, A]) extends ZFlow[R, E, A] {
    type ValueR = R

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
      Transaction(workflow.substitute(f))

    override private[flow] val variableUsage = workflow.variableUsage
  }

  object Transaction {
    def schema[R, E, A]: Schema[Transaction[R, E, A]] =
      Schema.defer(
        ZFlow
          .schema[R, E, A]
          .transform(
            Transaction(_),
            _.workflow
          )
      )

    def schemaCase[R, E, A]: Schema.Case[Transaction[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Transaction", schema[R, E, A], _.asInstanceOf[Transaction[R, E, A]])
  }

  final case class Unwrap[R, E, A](remote: Remote[ZFlow[R, E, A]]) extends ZFlow[R, E, A] {
    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
      Unwrap(remote.substitute(f))

    override private[flow] val variableUsage = remote.variableUsage
  }

  object Unwrap {
    def schema[R, E, A]: Schema[Unwrap[R, E, A]] =
      Schema.CaseClass1[Remote[ZFlow[R, E, A]], Unwrap[R, E, A]](
        Schema.Field("remote", Remote.schema[ZFlow[R, E, A]]),
        { case (remote) =>
          Unwrap(remote)
        },
        _.remote
      )

    def schemaCase[R, E, A]: Schema.Case[Unwrap[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Unwrap", schema[R, E, A], _.asInstanceOf[Unwrap[R, E, A]])
  }

  final case class UnwrapRemote[A](remote: Remote[Remote[A]]) extends ZFlow[Any, Nothing, A] {

    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, A] =
      UnwrapRemote(remote.substitute(f))

    override private[flow] val variableUsage = remote.variableUsage
  }

  object UnwrapRemote {
    def schema[A]: Schema[UnwrapRemote[A]] =
      Schema.CaseClass1[Remote[Remote[A]], UnwrapRemote[A]](
        Schema.Field("remote", Remote.schema[Remote[A]]),
        { case (remote) =>
          UnwrapRemote(remote)
        },
        _.remote
      )

    def schemaCase[R, E, A]: Schema.Case[UnwrapRemote[A], ZFlow[R, E, A]] =
      Schema.Case("UnwrapRemote", schema[A], _.asInstanceOf[UnwrapRemote[A]])
  }

  final case class WaitTill(time: Remote[Instant]) extends ZFlow[Any, Nothing, Unit] {
    override protected def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Unit] =
      WaitTill(time.substitute(f))

    override private[flow] val variableUsage = time.variableUsage
  }

  object WaitTill {
    def schema[A]: Schema[WaitTill] =
      Remote
        .schema[Instant]
        .transform(
          WaitTill(_),
          _.time
        )

    def schemaCase[R, E, A]: Schema.Case[WaitTill, ZFlow[R, E, A]] =
      Schema.Case("WaitTill", schema[A], _.asInstanceOf[WaitTill])
  }

  /**
   * Creates a flow that returns the given value.
   *
   * The value's type must have a Schema to be able to persist it in a
   * [[Remote]] value.
   */
  def apply[A: Schema](a: A): ZFlow[Any, ZNothing, A] = Return(Remote(a))

  /** Creates a flow that returns the given remote value */
  def apply[A](remote: Remote[A]): ZFlow[Any, ZNothing, A] = Return(remote)

  /** Creates a flow that fails with the given remote value */
  def fail[E](error: Remote[E]): ZFlow[Any, E, ZNothing] = ZFlow.Fail(error)

  /**
   * Creates a flow that executes the flow returned by the body function for
   * each element of the given values.
   *
   * The result of the flow is the list of each flow's result, in the same order
   * as the input values are.
   */
  def foreach[R, E, A, B: Schema](
    values: Remote[List[A]]
  )(body: Remote[A] => ZFlow[R, E, B]): ZFlow[R, E, List[B]] =
    ZFlow.unwrap {
      values.fold[ZFlow[R, E, List[B]]](ZFlow.succeed(Remote[List[B]](Nil))) { (bs, a) =>
        for {
          bs <- ZFlow.unwrap(bs)
          b  <- body(a)
        } yield Remote.Cons(bs, b)
      }
    }.map(_.reverse)

  /**
   * Creates a flow that executes the flow returned by the body function for
   * each element of the given values. The sub-flows are all executed in
   * parallel.
   *
   * The result of the flow is the list of each flow's result, in the same order
   * as the input values are.
   */
  def foreachPar[R, A, B: Schema](
    values: Remote[List[A]]
  )(body: Remote[A] => ZFlow[R, ActivityError, B]): ZFlow[R, ActivityError, List[B]] =
    for {
      executingFlows <- ZFlow.foreach[R, ZNothing, A, ExecutingFlow[ActivityError, B]](values) { (remoteA: Remote[A]) =>
                          body(remoteA).fork
                        }
      eithers <- ZFlow.foreach(executingFlows)(remote => remote.await)
      bs      <- ZFlow.fromEither(remote.RemoteEitherSyntax.collectAll(eithers))
    } yield bs

  /**
   * Creates a flow from a remote either value that either fails or succeeds
   * corresponding to the either value.
   */
  def fromEither[E, A](either: Remote[Either[E, A]]): ZFlow[Any, E, A] =
    ZFlow.unwrap(either.fold((e: Remote[E]) => ZFlow.fail(e), (a: Remote[A]) => ZFlow.succeed(a)))

  /**
   * Creates a flow that executes either the ifTrue flow or the ifFalse flow
   * based on the given remote boolean's value
   */
  def ifThenElse[R, E, A](
    p: Remote[Boolean]
  )(ifTrue: ZFlow[R, E, A], ifFalse: ZFlow[R, E, A]): ZFlow[R, E, A] =
    ZFlow.unwrap(p.ifThenElse(ifTrue, ifFalse))

  /** Creates a flow that returns the flow's input */
  def input[R]: ZFlow[R, ZNothing, R] = Input[R]()

  /**
   * Creates a flow that iterates a sub-flow starting from an initial value
   * until a given predicate evaluates to true.
   */
  def iterate[R, E, A](
    initial: Remote[A],
    step: Remote[A] => Remote[ZFlow[R, E, A]],
    predicate: Remote[A] => Remote[Boolean]
  ): ZFlow.Iterate[R, E, A] =
    ZFlow.Iterate(initial, UnboundRemoteFunction.make(step), UnboundRemoteFunction.make(predicate))

  /** Creates a flow that logs a string */
  def log(message: String): ZFlow[Any, ZNothing, Unit] = ZFlow.Log(Remote(message))

  /** Creates a flow that logs a remote string */
  def log(remoteMessage: Remote[String]): ZFlow[Any, ZNothing, Unit] = ZFlow.Log(remoteMessage)

  /**
   * Creates a flow that defines a new remote variable of type A, with a given
   * name and initial value.
   */
  def newVar[A](name: String, initial: Remote[A]): ZFlow[Any, ZNothing, RemoteVariableReference[A]] =
    NewVar(name, initial)

  /** Creates a flow that returns the current time */
  def now: ZFlow[Any, ZNothing, Instant] = Now

  /**
   * Creates a flow that repeats the given flow and stops when it evaluates to
   * true.
   */
  def repeatUntil[R, E](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(false).iterate((_: Remote[Boolean]) => flow)(_ === false)

  /**
   * Creates a flow that repeats the given flow and stops when it evaluates to
   * false.
   */
  def repeatWhile[R, E](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(true).iterate((_: Remote[Boolean]) => flow)(_ === true)

  /** Creates a flow that waits for the given duration */
  def sleep(duration: Remote[Duration]): ZFlow[Any, ZNothing, Unit] =
    ZFlow.now.flatMap { now =>
      ZFlow(now.plusDuration(duration)).flatMap { later =>
        ZFlow.waitTill(later).map { _ =>
          Remote.unit
        }
      }
    }

  /** Creates a flow that returns the given remote value */
  def succeed[A](value: Remote[A]): ZFlow[Any, ZNothing, A] = ZFlow.Return(value)

  /**
   * Creates a transactional flow.
   *
   * Within a transaction each accessed remote variable is tracked and in case
   * they got modified from another flow before the transaction finishes, the
   * whole transaction is going to be retried.
   *
   * Activities executed within a transaction gets reverted in case of failure.
   */
  def transaction[R, E, A](
    make: ZFlowTransaction => ZFlow[R, E, A]
  ): ZFlow[R, E, A] =
    Transaction(make(ZFlowTransaction.instance))

  /** A flow that returns the unit value */
  val unit: ZFlow[Any, ZNothing, Unit] = ZFlow(Remote.unit)

  /** Creates a flow that unwraps a remote flow value and executes it */
  def unwrap[R, E, A](remote: Remote[ZFlow[R, E, A]]): ZFlow[R, E, A] =
    Unwrap(remote)

  /** Creates a flow that unwraps a nested remote value */
  def unwrapRemote[A](remote: Remote[Remote[A]]): ZFlow[Any, ZNothing, A] =
    UnwrapRemote(remote)

  /** Creates a flow that suspends execution until a given point in time */
  def waitTill(instant: Remote[Instant]): ZFlow[Any, ZNothing, Unit] = WaitTill(instant)

  /**
   * Creates a flow that only runs the given flow if the given predicate is true
   */
  def when[R, E, A](predicate: Remote[Boolean])(
    flow: ZFlow[R, E, A]
  ): ZFlow[R, E, Unit] =
    ZFlow.ifThenElse[R, E, Unit](predicate)(flow.unit, ZFlow.unit)

  private def createSchema[R, E, A]: Schema[ZFlow[R, E, A]] =
    Schema.EnumN(
      CaseSet
        .Cons(Return.schemaCase[R, E, A], CaseSet.Empty[ZFlow[R, E, A]]())
        .:+:(Now.schemaCase[R, E, A])
        .:+:(WaitTill.schemaCase[R, E, A])
        .:+:(Read.schemaCase[R, E, A])
        .:+:(Modify.schemaCase[R, E, A])
        .:+:(Fold.schemaCase[R, E, A])
        .:+:(Log.schemaCase[R, E, A])
        .:+:(RunActivity.schemaCase[R, E, A])
        .:+:(Transaction.schemaCase[R, E, A])
        .:+:(Input.schemaCase[R, E, A])
        .:+:(Ensuring.schemaCase[R, E, A])
        .:+:(Unwrap.schemaCase[R, E, A])
        .:+:(UnwrapRemote.schemaCase[R, E, A])
        .:+:(Fork.schemaCase[R, E, A])
        .:+:(Timeout.schemaCase[R, E, A])
        .:+:(Provide.schemaCase[R, E, A])
        .:+:(Die.schemaCase[R, E, A])
        .:+:(RetryUntil.schemaCase[R, E, A])
        .:+:(OrTry.schemaCase[R, E, A])
        .:+:(Await.schemaCase[R, E, A])
        .:+:(Interrupt.schemaCase[R, E, A])
        .:+:(NewVar.schemaCase[R, E, A])
        .:+:(Fail.schemaCase[R, E, A])
        .:+:(Iterate.schemaCase[R, E, A])
    )

  lazy val schemaAny: Schema[ZFlow[Any, Any, Any]]     = createSchema[Any, Any, Any]
  implicit def schema[R, E, A]: Schema[ZFlow[R, E, A]] = schemaAny.asInstanceOf[Schema[ZFlow[R, E, A]]]
}
