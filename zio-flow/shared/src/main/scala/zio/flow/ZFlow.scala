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

sealed trait ZFlow[-R, +E, +A] {
  self =>

  private[flow] val variableUsage: VariableUsage

  final def *>[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    self.zip[R1, E1, A1, B](that).map((a: Remote[(A1, B)]) => a._2)

  final def <*[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, A1] =
    self.zip[R1, E1, A1, B](that).map((a: Remote[(A1, B)]) => a._1)

  final def as[B](b: => Remote[B]): ZFlow[R, E, B] =
    self.map[B](_ => b)

  final def catchAll[R1 <: R, E1 >: E, A1 >: A, E2](
    f: Remote[E1] => ZFlow[R1, E2, A1]
  ): ZFlow[R1, E2, A1] =
    self.foldFlow(f, (a: Remote[A1]) => ZFlow(a))

  final def ensuring[E1 >: E, A1 >: A](
    flow: ZFlow[Any, ZNothing, Any]
  ): ZFlow[R, E1, A1] =
    ZFlow.Ensuring[R, E1, A1](self, flow.unit)

  final def flatMap[R1 <: R, E1 >: E, B](
    f: Remote[A] => ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    foldFlow(
      (e: Remote[E1]) => ZFlow.Fail(e),
      f
    )

  final def foldFlow[R1 <: R, E2, B](
    error: Remote[E] => ZFlow[R1, E2, B],
    success: Remote[A] => ZFlow[R1, E2, B]
  ): ZFlow[R1, E2, B] =
    ZFlow.Fold(
      self,
      EvaluatedRemoteFunction.make(error.andThen(_.toRemote)),
      EvaluatedRemoteFunction.make(success.andThen(_.toRemote))
    )

  final def fork: ZFlow[R, ZNothing, ExecutingFlow[E, A]] =
    ZFlow.Fork(self)

  final def ifThenElse[R1 <: R, E1 >: E, B](
    ifTrue: ZFlow[R1, E1, B],
    ifFalse: ZFlow[R1, E1, B]
  )(implicit
    ev: A <:< Boolean
  ): ZFlow[R1, E1, B] =
    self
      .widen[Boolean]
      .flatMap((bool: Remote[Boolean]) => ZFlow.unwrap(bool.ifThenElse(ifTrue.toRemote, ifFalse.toRemote)))

  final def iterate[R1 <: R, E1 >: E, A1 >: A](step: Remote[A1] => ZFlow[R1, E1, A1])(
    predicate: Remote[A1] => Remote[Boolean]
  ): ZFlow[R1, E1, A1] =
    self.flatMap((remoteA: Remote[A1]) =>
      ZFlow.Iterate(
        remoteA,
        EvaluatedRemoteFunction.make((a1: Remote[A1]) => step(a1).toRemote),
        EvaluatedRemoteFunction.make(predicate)
      )
    )

  final def map[B](f: Remote[A] => Remote[B]): ZFlow[R, E, B] =
    self.flatMap[R, E, B](a => ZFlow(f(a)))

  final def orDie[R1 <: R, E1 >: E, A1 >: A]: ZFlow[R1, ZNothing, A1] =
    self.catchAll[R1, E1, A1, ZNothing]((_: Remote[E1]) => ZFlow.Die)

  final def orElse[R1 <: R, E1 >: E, E2, A1 >: A](
    that: ZFlow[R1, E2, A1]
  ): ZFlow[R1, E2, A1] =
    self.catchAll((_: Remote[E1]) => that)

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

  final def provide(value: Remote[R]): ZFlow[Any, E, A] = ZFlow.Provide(value, self)

  final def timeout(
    duration: Remote[Duration]
  ): ZFlow[R, E, Option[A]] =
    ZFlow.Timeout[R, E, A](self, duration)

  final def unit: ZFlow[R, E, Unit] = as(())

  final def zip[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, (A1, B)] =
    self.flatMap((a: Remote[A1]) => that.map((b: Remote[B]) => a -> b))

  final def widen[A0](implicit ev: A <:< A0): ZFlow[R, E, A0] = {
    val _ = ev

    self.asInstanceOf[ZFlow[R, E, A0]]
  }

  final def substitute[B](f: Remote.Substitutions): ZFlow[R, E, A] =
    if (f.cut(variableUsage)) this else substituteRec(f)

  protected def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A]
}

object ZFlow {

  final case class Return[A](value: Remote[A]) extends ZFlow[Any, Nothing, A] {
    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, A] =
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

  case object Now extends ZFlow[Any, Nothing, Instant] {
    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Instant] =
      Now

    val schema: Schema[Now.type] = Schema.singleton(Now)
    def schemaCase[R, E, A]: Schema.Case[Now.type, ZFlow[R, E, A]] =
      Schema.Case("Now", schema, _.asInstanceOf[Now.type])

    override private[flow] val variableUsage = VariableUsage.none
  }

  final case class WaitTill(time: Remote[Instant]) extends ZFlow[Any, Nothing, Unit] {
    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Unit] =
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

  final case class Read[A](svar: Remote[RemoteVariableReference[A]]) extends ZFlow[Any, Nothing, A] {
    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, A] =
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

  final case class Modify[A, B](svar: Remote[RemoteVariableReference[A]], f: EvaluatedRemoteFunction[A, (B, A)])
      extends ZFlow[Any, Nothing, B] {
    override def substituteRec[C](fn: Remote.Substitutions): ZFlow[Any, Nothing, B] =
      Modify(
        svar.substitute(fn),
        f.substitute(fn).asInstanceOf[EvaluatedRemoteFunction[A, (B, A)]]
      )

    override private[flow] val variableUsage = svar.variableUsage.union(f.variableUsage)
  }

  object Modify {
    def schema[A, B]: Schema[Modify[A, B]] =
      Schema.CaseClass2[Remote[RemoteVariableReference[A]], EvaluatedRemoteFunction[A, (B, A)], Modify[
        A,
        B
      ]](
        Schema.Field("svar", Remote.schema[RemoteVariableReference[A]]),
        Schema.Field("f", EvaluatedRemoteFunction.schema[A, (B, A)]),
        { case (svar, f) =>
          Modify(svar, f)
        },
        _.svar,
        _.f
      )

    def schemaCase[R, E, A]: Schema.Case[Modify[Any, Any], ZFlow[R, E, A]] =
      Schema.Case("Modify", schema[Any, Any], _.asInstanceOf[Modify[Any, Any]])
  }

  final case class Fold[R, E, E2, A, B](
    value: ZFlow[R, E, A],
    ifError: EvaluatedRemoteFunction[E, ZFlow[R, E2, B]],
    ifSuccess: EvaluatedRemoteFunction[A, ZFlow[R, E2, B]]
  ) extends ZFlow[R, E2, B] {
    type ValueE  = E
    type ValueE2 = E2
    type ValueA  = A
    type ValueR  = R
    type ValueB  = B

    override def substituteRec[C](f: Remote.Substitutions): ZFlow[R, E2, B] =
      Fold(
        value.substitute(f),
        ifError.substitute(f).asInstanceOf[EvaluatedRemoteFunction[E, ZFlow[R, E2, B]]],
        ifSuccess.substitute(f).asInstanceOf[EvaluatedRemoteFunction[A, ZFlow[R, E2, B]]]
      )

    override private[flow] val variableUsage =
      value.variableUsage.union(ifError.variableUsage).union(ifSuccess.variableUsage)
  }

  object Fold {
    def schema[R, E, E2, A, B]: Schema[Fold[R, E, E2, A, B]] =
      Schema.defer(
        Schema.CaseClass3[ZFlow[R, E, A], EvaluatedRemoteFunction[E, ZFlow[R, E2, B]], EvaluatedRemoteFunction[
          A,
          ZFlow[R, E2, B]
        ], Fold[R, E, E2, A, B]](
          Schema.Field("value", ZFlow.schema[R, E, A]),
          Schema.Field("ifError", EvaluatedRemoteFunction.schema[E, ZFlow[R, E2, B]]),
          Schema.Field("ifSuccess", EvaluatedRemoteFunction.schema[A, ZFlow[R, E2, B]]),
          (value, ifError, ifSuccess) => Fold(value, ifError, ifSuccess),
          _.value,
          _.ifError,
          _.ifSuccess
        )
      )

    def schemaCase[R, E, A]: Schema.Case[ZFlow.Fold[Any, Any, Any, Any, Any], ZFlow[R, E, A]] =
      Schema.Case("Fold", schema[Any, Any, Any, Any, Any], _.asInstanceOf[Fold[Any, Any, Any, Any, Any]])
  }

  final case class Log(message: Remote[String]) extends ZFlow[Any, Nothing, Unit] {

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Unit] =
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

  final case class RunActivity[R, A](input: Remote[R], activity: Activity[R, A]) extends ZFlow[Any, ActivityError, A] {

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, ActivityError, A] =
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

  final case class Transaction[R, E, A](workflow: ZFlow[R, E, A]) extends ZFlow[R, E, A] {
    type ValueR = R

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
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

  final case class Input[R]() extends ZFlow[R, Nothing, R] {

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[R, Nothing, R] =
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

  final case class Ensuring[R, E, A](flow: ZFlow[R, E, A], finalizer: ZFlow[Any, ZNothing, Unit])
      extends ZFlow[R, E, A] {
    type ValueE = E
    type ValueA = A

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
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

  final case class Unwrap[R, E, A](remote: Remote[ZFlow[R, E, A]]) extends ZFlow[R, E, A] {
    override def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
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

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, A] =
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

  final case class Fork[R, E, A](flow: ZFlow[R, E, A]) extends ZFlow[R, Nothing, ExecutingFlow[E, A]] {
    type ValueE = E
    type ValueA = A

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[R, Nothing, ExecutingFlow[E, A]] =
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

  final case class Timeout[R, E, A](flow: ZFlow[R, E, A], duration: Remote[Duration]) extends ZFlow[R, E, Option[A]] {
    type ValueA = A
    type ValueE = E

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, Option[A]] =
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

  final case class Provide[R, E, A](value: Remote[R], flow: ZFlow[R, E, A]) extends ZFlow[Any, E, A] {
    type ValueE = E
    type ValueA = A

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, E, A] =
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

  case object Die extends ZFlow[Any, Nothing, Nothing] {

    val schema: Schema[Die.type] = Schema.singleton(Die)
    def schemaCase[R, E, A]: Schema.Case[Die.type, ZFlow[R, E, A]] =
      Schema.Case("Die", schema, _.asInstanceOf[Die.type])

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Nothing] =
      Die

    override private[flow] val variableUsage = VariableUsage.none
  }

  case object RetryUntil extends ZFlow[Any, Nothing, Nothing] {

    val schema: Schema[RetryUntil.type] = Schema.singleton(RetryUntil)
    def schemaCase[R, E, A]: Schema.Case[RetryUntil.type, ZFlow[R, E, A]] =
      Schema.Case("RetryUntil", schema, _.asInstanceOf[RetryUntil.type])

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, Nothing] =
      RetryUntil

    override private[flow] val variableUsage = VariableUsage.none
  }

  final case class OrTry[R, E, A](left: ZFlow[R, E, A], right: ZFlow[R, E, A]) extends ZFlow[R, E, A] {

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
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

  final case class Await[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Either[E, A]] {
    type ValueE = E
    type ValueA = A

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, ActivityError, Either[E, A]] =
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

  final case class Interrupt[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Unit] {

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, ActivityError, Unit] =
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

  final case class Fail[E](error: Remote[E]) extends ZFlow[Any, E, Nothing] {

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, E, Nothing] =
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

  final case class NewVar[A](name: String, initial: Remote[A]) extends ZFlow[Any, Nothing, RemoteVariableReference[A]] {

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[Any, Nothing, RemoteVariableReference[A]] =
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

  final case class Iterate[R, E, A](
    initial: Remote[A],
    step: EvaluatedRemoteFunction[A, ZFlow[R, E, A]],
    predicate: EvaluatedRemoteFunction[A, Boolean]
  ) extends ZFlow[R, E, A] {
    type ValueE = E
    type ValueA = A

    override def substituteRec[B](f: Remote.Substitutions): ZFlow[R, E, A] =
      Iterate(
        initial.substitute(f),
        step.substitute(f).asInstanceOf[EvaluatedRemoteFunction[A, ZFlow[R, E, A]]],
        predicate.substitute(f).asInstanceOf[EvaluatedRemoteFunction[A, Boolean]]
      )

    override private[flow] val variableUsage =
      initial.variableUsage.union(step.variableUsage).union(predicate.variableUsage)
  }

  object Iterate {
    def schema[R, E, A]: Schema[Iterate[R, E, A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[A], EvaluatedRemoteFunction[A, ZFlow[R, E, A]], EvaluatedRemoteFunction[
          A,
          Boolean
        ], Iterate[R, E, A]](
          Schema.Field("initial", Remote.schema[A]),
          Schema.Field("step", EvaluatedRemoteFunction.schema[A, ZFlow[R, E, A]]),
          Schema.Field("predicate", EvaluatedRemoteFunction.schema[A, Boolean]),
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

  def apply[A: Schema](a: A): ZFlow[Any, ZNothing, A] = Return(Remote(a))

  def apply[A](remote: Remote[A]): ZFlow[Any, ZNothing, A] = Return(remote)

  def doUntil[R, E](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(false).iterate((_: Remote[Boolean]) => flow)(_ === false)

  def doWhile[R, E](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(true).iterate((_: Remote[Boolean]) => flow)(_ === true)

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

  def foreachPar[R, A, B: Schema](
    values: Remote[List[A]]
  )(body: Remote[A] => ZFlow[R, ActivityError, B]): ZFlow[R, ActivityError, List[B]] =
    for {
      executingFlows <- ZFlow.foreach[R, ZNothing, A, ExecutingFlow[ActivityError, B]](values) { (remoteA: Remote[A]) =>
                          body(remoteA).fork
                        }
      _       <- log("Awaiting results")
      eithers <- ZFlow.foreach(executingFlows)(remote => remote.await)
      _       <- log("Got results from all forked fibers")
      bs      <- ZFlow.fromEither(remote.RemoteEitherSyntax.collectAll(eithers))
    } yield bs

  def ifThenElse[R, E, A](
    p: Remote[Boolean]
  )(ifTrue: ZFlow[R, E, A], ifFalse: ZFlow[R, E, A]): ZFlow[R, E, A] =
    ZFlow.unwrap(p.ifThenElse(ifTrue, ifFalse))

  def input[R]: ZFlow[R, ZNothing, R] = Input[R]()

  def newVar[A](name: String, initial: Remote[A]): ZFlow[Any, ZNothing, RemoteVariableReference[A]] =
    NewVar(name, initial)

  def now: ZFlow[Any, ZNothing, Instant] = Now

  def sleep(duration: Remote[Duration]): ZFlow[Any, ZNothing, Unit] =
    ZFlow.now.flatMap { now =>
      ZFlow(now.plusDuration(duration)).flatMap { later =>
        ZFlow.waitTill(later).map { _ =>
          Remote.unit
        }
      }
    }

  def transaction[R, E, A](
    make: ZFlowTransaction => ZFlow[R, E, A]
  ): ZFlow[R, E, A] =
    Transaction(make(ZFlowTransaction.instance))

  val unit: ZFlow[Any, ZNothing, Unit] = ZFlow(Remote.unit)

  def unwrap[R, E, A](remote: Remote[ZFlow[R, E, A]]): ZFlow[R, E, A] =
    Unwrap(remote)

  def unwrapRemote[A](remote: Remote[Remote[A]]): ZFlow[Any, ZNothing, A] =
    UnwrapRemote(remote)

  def waitTill(instant: Remote[Instant]): ZFlow[Any, ZNothing, Unit] = WaitTill(instant)

  def when[R, E, A](predicate: Remote[Boolean])(
    flow: ZFlow[R, E, A]
  ): ZFlow[R, E, Unit] =
    ZFlow.ifThenElse[R, E, Unit](predicate)(flow.unit, ZFlow.unit)

  def fail[E](error: Remote[E]): ZFlow[Any, E, ZNothing] = ZFlow.Fail(error)

  def succeed[A](value: Remote[A]): ZFlow[Any, ZNothing, A] = ZFlow.Return(value)

  def fromEither[E, A](either: Remote[Either[E, A]]): ZFlow[Any, E, A] =
    ZFlow.unwrap(either.fold((e: Remote[E]) => ZFlow.fail(e), (a: Remote[A]) => ZFlow.succeed(a)))

  def log(message: String): ZFlow[Any, ZNothing, Unit] = ZFlow.Log(Remote(message))

  def log(remoteMessage: Remote[String]): ZFlow[Any, ZNothing, Unit] = ZFlow.Log(remoteMessage)

  def iterate[R, E, A](
    initial: Remote[A],
    step: Remote[A] => Remote[ZFlow[R, E, A]],
    predicate: Remote[A] => Remote[Boolean]
  ): ZFlow.Iterate[R, E, A] =
    ZFlow.Iterate(initial, EvaluatedRemoteFunction.make(step), EvaluatedRemoteFunction.make(predicate))

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
