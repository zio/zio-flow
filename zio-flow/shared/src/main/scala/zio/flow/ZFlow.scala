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

import zio.flow.Remote._
import zio.flow.serialization.FlowSchemaAst
import zio.schema.{CaseSet, Schema}
import zio.ZNothing

import java.time.{Duration, Instant}

// ZFlow - models a workflow
//  - terminate, either error or value
//  - create instances that represent running executions of a workflow in progress
//  - instances have persistent state that can be changed in a semi-transactional ways
//  - instance state can be persisted in transparent, introspectable way (e.g. JSON)
//  - business logic
//    - changing in response to external input (events)
//    - initiate activities (interactions with the outside world)
//
// Activity - models an interaction with the outside world
//  - test to see if activity is completed
//  - compensation (undo an activity), "saga pattern"
//  - examples: microservice interaction, REST API call, GraphQL query, database query
//
sealed trait ZFlow[-R, +E, +A] {
  self =>

  val errorSchema: Schema[_ <: E]
  val resultSchema: Schema[_ <: A]

  final def *>[R1 <: R, E1 >: E: Schema, A1 >: A: Schema, B: Schema](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    self.zip[R1, E1, A1, B](that).map((a: Remote[(A1, B)]) => a._2)

  final def <*[R1 <: R, E1 >: E: Schema, A1 >: A: Schema, B: Schema](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, A1] =
    self.zip[R1, E1, A1, B](that).map((a: Remote[(A1, B)]) => a._1)

  final def as[B: Schema](b: => Remote[B]): ZFlow[R, E, B] =
    self.map[B](_ => b)

  final def catchAll[R1 <: R, E1 >: E, A1 >: A: Schema, E2: Schema](
    f: Remote[E1] => ZFlow[R1, E2, A1]
  ): ZFlow[R1, E2, A1] =
    self.foldM(f, (a: Remote[A1]) => ZFlow(a))

  final def ensuring[R1 <: R: Schema, E1 >: E: Schema, A1 >: A: Schema](
    flow: ZFlow[R1, Nothing, Any]
  ): ZFlow[R1, E1, A1] =
    ZFlow
      .input[R1]
      .flatMap[R1, E1, A1]((r: Remote[R1]) => ZFlow.Ensuring(self, flow.unit.provide(r)))

  final def ensuring[E1 >: E: Schema, A1 >: A: Schema](
    flow: ZFlow[Any, ZNothing, Any]
  ): ZFlow[R, E1, A1] =
    ZFlow.Ensuring[R, E1, A1](self, flow.unit)

  final def flatMap[R1 <: R, E1 >: E: Schema, B: Schema](
    f: Remote[A] => ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    foldM(
      (e: Remote[E1]) => ZFlow.Fail(e),
      f
    )

  final def foldM[R1 <: R, E2: Schema, B: Schema](
    error: Remote[E] => ZFlow[R1, E2, B],
    success: Remote[A] => ZFlow[R1, E2, B]
  ): ZFlow[R1, E2, B] =
    ZFlow.Fold(
      self,
      RemoteFunction(error.andThen(_.toRemote))(
        errorSchema.asInstanceOf[Schema[E]]
      ).evaluated,
      RemoteFunction(success.andThen(_.toRemote))(
        resultSchema.asInstanceOf[Schema[A]]
      ).evaluated
    )

  final def fork: ZFlow[R, ZNothing, ExecutingFlow[E, A]] =
    ZFlow.Fork(self)

  final def ifThenElse[R1 <: R, E1 >: E: Schema, B: Schema](
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
  )(implicit schemaA: Schema[A1], schemaE: Schema[E1]): ZFlow[R1, E1, A1] =
    self.flatMap((remoteA: Remote[A1]) =>
      ZFlow.Iterate(
        remoteA,
        RemoteFunction((a1: Remote[A1]) => step(a1).toRemote)(schemaA).evaluated,
        RemoteFunction(predicate)(schemaA).evaluated
      )
    )

  final def map[B: Schema](f: Remote[A] => Remote[B]): ZFlow[R, E, B] =
    self.flatMap[R, E, B](a => ZFlow(f(a)))(errorSchema.asInstanceOf[Schema[E]], Schema[B])

  final def orDie[R1 <: R, E1 >: E, A1 >: A: Schema]: ZFlow[R1, ZNothing, A1] =
    self.catchAll[R1, E1, A1, ZNothing]((_: Remote[E1]) => ZFlow.Die)

  final def orElse[R1 <: R, E1 >: E, E2: Schema, A1 >: A: Schema](
    that: ZFlow[R1, E2, A1]
  ): ZFlow[R1, E2, A1] =
    self.catchAll((_: Remote[E1]) => that)

  final def orElseEither[R1 <: R, A1 >: A, E2: Schema, B](
    that: ZFlow[R1, E2, B]
  )(implicit schemaA: Schema[A1], schemaB: Schema[B]): ZFlow[R1, E2, Either[A1, B]] =
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

  final def zip[R1 <: R, E1 >: E: Schema, A1 >: A: Schema, B: Schema](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, (A1, B)] =
    self.flatMap((a: Remote[A1]) => that.map((b: Remote[B]) => a -> b))

  final def widen[A0](implicit ev: A <:< A0): ZFlow[R, E, A0] = {
    val _ = ev

    self.asInstanceOf[ZFlow[R, E, A0]]
  }
}

object ZFlow {

  final case class Return[A](value: Remote[A]) extends ZFlow[Any, Nothing, A] {
    val errorSchema  = Schema[ZNothing]
    val resultSchema = value.schema
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
    val errorSchema                   = Schema[ZNothing]
    val resultSchema: Schema[Instant] = Schema[Instant]

    val schema: Schema[Now.type] = Schema.singleton(Now)
    def schemaCase[R, E, A]: Schema.Case[Now.type, ZFlow[R, E, A]] =
      Schema.Case("Now", schema, _.asInstanceOf[Now.type])
  }

  final case class WaitTill(time: Remote[Instant]) extends ZFlow[Any, Nothing, Unit] {
    val errorSchema                = Schema[ZNothing]
    val resultSchema: Schema[Unit] = Schema[Unit]
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

  final case class Modify[A, B](svar: Remote[RemoteVariableReference[A]], f: EvaluatedRemoteFunction[A, (B, A)])
      extends ZFlow[Any, Nothing, B] {
    val errorSchema  = Schema[ZNothing]
    val resultSchema = f.schema.asInstanceOf[Schema.Tuple[B, A]].left
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
  )(implicit
    val errorSchema: Schema[E2],
    val resultSchema: Schema[B]
  ) extends ZFlow[R, E2, B] {
    type ValueE  = E
    type ValueE2 = E2
    type ValueA  = A
    type ValueR  = R
    type ValueB  = B
  }

  object Fold {
    def schema[R, E, E2, A, B]: Schema[Fold[R, E, E2, A, B]] =
      Schema.defer(
        Schema.CaseClass5[ZFlow[R, E, A], EvaluatedRemoteFunction[E, ZFlow[R, E2, B]], EvaluatedRemoteFunction[
          A,
          ZFlow[R, E2, B]
        ], FlowSchemaAst, FlowSchemaAst, Fold[R, E, E2, A, B]](
          Schema.Field("value", ZFlow.schema[R, E, A]),
          Schema.Field("ifError", EvaluatedRemoteFunction.schema[E, ZFlow[R, E2, B]]),
          Schema.Field("ifSuccess", EvaluatedRemoteFunction.schema[A, ZFlow[R, E2, B]]),
          Schema.Field("errorSchema", FlowSchemaAst.schema),
          Schema.Field("resultSchema", FlowSchemaAst.schema),
          { case (value, ifError, ifSuccess, errorSchema, resultSchema) =>
            Fold(value, ifError, ifSuccess)(
              errorSchema.toSchema[E2],
              resultSchema.toSchema[B]
            )
          },
          _.value,
          _.ifError,
          _.ifSuccess,
          flow => FlowSchemaAst.fromSchema(flow.errorSchema),
          flow => FlowSchemaAst.fromSchema(flow.resultSchema)
        )
      )

    def schemaCase[R, E, A]: Schema.Case[ZFlow.Fold[Any, Any, Any, Any, Any], ZFlow[R, E, A]] =
      Schema.Case("Fold", schema[Any, Any, Any, Any, Any], _.asInstanceOf[Fold[Any, Any, Any, Any, Any]])
  }

  final case class Log(message: Remote[String]) extends ZFlow[Any, Nothing, Unit] {
    val errorSchema                = Schema[ZNothing]
    val resultSchema: Schema[Unit] = Schema[Unit]
  }

  object Log {
    val schema: Schema[Log] =
      Schema.defer {
        Remote.schema[String].transform(Log(_), _.message)
      }

    def schemaCase[R, E, A]: Schema.Case[ZFlow.Log, ZFlow[R, E, A]] =
      Schema.Case("Log", schema, _.asInstanceOf[Log])
  }

  // TODO: why not get the input from the flow environment?
  final case class RunActivity[R, A](input: Remote[R], activity: Activity[R, A]) extends ZFlow[Any, ActivityError, A] {
    val errorSchema  = Schema[ActivityError]
    val resultSchema = activity.resultSchema
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
    override val errorSchema  = workflow.errorSchema
    override val resultSchema = workflow.resultSchema
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

  final case class Input[R]()(implicit val resultSchema: Schema[R]) extends ZFlow[R, Nothing, R] {
    val errorSchema = Schema[ZNothing]
  }

  object Input {
    def schema[R]: Schema[Input[R]] =
      FlowSchemaAst.schema.transform(
        ast => Input()(ast.toSchema[R]),
        flow => FlowSchemaAst.fromSchema(flow.resultSchema)
      )

    def schemaCase[R, E, A]: Schema.Case[Input[R], ZFlow[R, E, A]] =
      Schema.Case("Input", schema[R], _.asInstanceOf[Input[R]])
  }

  final case class Ensuring[R, E, A](flow: ZFlow[R, E, A], finalizer: ZFlow[Any, ZNothing, Unit])
      extends ZFlow[R, E, A] {
    type ValueE = E
    type ValueA = A

    val errorSchema  = flow.errorSchema
    val resultSchema = flow.resultSchema
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

  final case class Unwrap[R, E, A](remote: Remote[ZFlow[R, E, A]])(implicit
    val errorSchema: Schema[E],
    val resultSchema: Schema[A]
  ) extends ZFlow[R, E, A]

  object Unwrap {
    def schema[R, E, A]: Schema[Unwrap[R, E, A]] =
      Schema.CaseClass3[Remote[ZFlow[R, E, A]], FlowSchemaAst, FlowSchemaAst, Unwrap[R, E, A]](
        Schema.Field("remote", Remote.schema[ZFlow[R, E, A]]),
        Schema.Field("errorSchema", FlowSchemaAst.schema),
        Schema.Field("resultSchema", FlowSchemaAst.schema),
        { case (remote, errorSchemaAst, resultSchemaAst) =>
          Unwrap(remote)(
            errorSchemaAst.toSchema[E],
            resultSchemaAst.toSchema[A]
          )
        },
        _.remote,
        flow => FlowSchemaAst.fromSchema(flow.errorSchema),
        flow => FlowSchemaAst.fromSchema(flow.resultSchema)
      )

    def schemaCase[R, E, A]: Schema.Case[Unwrap[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Unwrap", schema[R, E, A], _.asInstanceOf[Unwrap[R, E, A]])
  }

  final case class UnwrapRemote[A](remote: Remote[Remote[A]])(implicit
    val resultSchema: Schema[A]
  ) extends ZFlow[Any, Nothing, A] {
    val errorSchema = Schema[ZNothing]
  }

  object UnwrapRemote {
    def schema[A]: Schema[UnwrapRemote[A]] =
      Schema.CaseClass2[Remote[Remote[A]], FlowSchemaAst, UnwrapRemote[A]](
        Schema.Field("remote", Remote.schema[Remote[A]]),
        Schema.Field("resultSchema", FlowSchemaAst.schema),
        { case (remote, resultSchemaAst) =>
          UnwrapRemote(remote)(
            resultSchemaAst.toSchema[A]
          )
        },
        _.remote,
        flow => FlowSchemaAst.fromSchema(flow.resultSchema)
      )

    def schemaCase[R, E, A]: Schema.Case[UnwrapRemote[A], ZFlow[R, E, A]] =
      Schema.Case("UnwrapRemote", schema[A], _.asInstanceOf[UnwrapRemote[A]])
  }

  final case class Fork[R, E, A](flow: ZFlow[R, E, A]) extends ZFlow[R, Nothing, ExecutingFlow[E, A]] {
    type ValueE = E
    type ValueA = A
    val errorSchema = Schema[ZNothing]
    val resultSchema: Schema[ExecutingFlow[E, A]] =
      Schema[ExecutingFlow[E, A]](ExecutingFlow.schema)
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
    val errorSchema  = flow.errorSchema
    val resultSchema = Schema.option(flow.resultSchema)
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
    val errorSchema  = flow.errorSchema
    val resultSchema = flow.resultSchema
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
    val errorSchema  = Schema[ZNothing]
    val resultSchema = Schema[ZNothing]

    val schema: Schema[Die.type] = Schema.singleton(Die)
    def schemaCase[R, E, A]: Schema.Case[Die.type, ZFlow[R, E, A]] =
      Schema.Case("Die", schema, _.asInstanceOf[Die.type])
  }

  case object RetryUntil extends ZFlow[Any, Nothing, Nothing] {
    val errorSchema  = Schema[ZNothing]
    val resultSchema = Schema[ZNothing]

    val schema: Schema[RetryUntil.type] = Schema.singleton(RetryUntil)
    def schemaCase[R, E, A]: Schema.Case[RetryUntil.type, ZFlow[R, E, A]] =
      Schema.Case("RetryUntil", schema, _.asInstanceOf[RetryUntil.type])
  }

  final case class OrTry[R, E, A](left: ZFlow[R, E, A], right: ZFlow[R, E, A]) extends ZFlow[R, E, A] {
    val errorSchema  = left.errorSchema
    val resultSchema = left.resultSchema
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

  final case class Await[E, A](exFlow: Remote[ExecutingFlow[E, A]])(implicit
    val schemaE: Schema[E],
    val schemaA: Schema[A]
  ) extends ZFlow[Any, ActivityError, Either[E, A]] {
    type ValueE = E
    type ValueA = A
    val errorSchema  = Schema[ActivityError]
    val resultSchema = Schema[Either[E, A]]
  }

  object Await {
    def schema[E, A]: Schema[Await[E, A]] =
      Schema.CaseClass3[Remote[ExecutingFlow[E, A]], FlowSchemaAst, FlowSchemaAst, Await[E, A]](
        Schema.Field("exFlow", Remote.schema[ExecutingFlow[E, A]]),
        Schema.Field("schemaE", FlowSchemaAst.schema),
        Schema.Field("schemaA", FlowSchemaAst.schema),
        { case (exFlow, schemaAstE, schemaAstA) =>
          Await(exFlow)(
            schemaAstE.toSchema[E],
            schemaAstA.toSchema[A]
          )
        },
        _.exFlow,
        flow => FlowSchemaAst.fromSchema(flow.schemaE),
        flow => FlowSchemaAst.fromSchema(flow.schemaA)
      )

    def schemaCase[R, E, A]: Schema.Case[Await[E, A], ZFlow[R, E, A]] =
      Schema.Case("Await", schema[E, A], _.asInstanceOf[Await[E, A]])
  }

  final case class Interrupt[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Unit] {
    val errorSchema  = Schema[ActivityError]
    val resultSchema = Schema[Unit]
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
    override val errorSchema  = error.schema
    override val resultSchema = Schema[ZNothing]
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
    val errorSchema  = Schema[ZNothing]
    val resultSchema = RemoteVariableReference.schema[A]
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
  )(implicit
    val errorSchema: Schema[E],
    val resultSchema: Schema[A]
  ) extends ZFlow[R, E, A] {
    type ValueE = E
    type ValueA = A
  }

  object Iterate {
    def schema[R, E, A]: Schema[Iterate[R, E, A]] =
      Schema.defer(
        Schema.CaseClass5[Remote[A], EvaluatedRemoteFunction[A, ZFlow[R, E, A]], EvaluatedRemoteFunction[
          A,
          Boolean
        ], FlowSchemaAst, FlowSchemaAst, Iterate[R, E, A]](
          Schema.Field("initial", Remote.schema[A]),
          Schema.Field("step", EvaluatedRemoteFunction.schema[A, ZFlow[R, E, A]]),
          Schema.Field("predicate", EvaluatedRemoteFunction.schema[A, Boolean]),
          Schema.Field("errorSchema", FlowSchemaAst.schema),
          Schema.Field("resultSchema", FlowSchemaAst.schema),
          { case (initial, step, predicate, errorSchema, resultSchema) =>
            Iterate(initial, step, predicate)(
              errorSchema.toSchema[E],
              resultSchema.toSchema[A]
            )
          },
          _.initial,
          _.step,
          _.predicate,
          flow => FlowSchemaAst.fromSchema(flow.errorSchema),
          flow => FlowSchemaAst.fromSchema(flow.resultSchema)
        )
      )

    def schemaCase[R, E, A]: Schema.Case[Iterate[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Iterate", schema[R, E, A], _.asInstanceOf[Iterate[R, E, A]])
  }

  case object GetExecutionEnvironment extends ZFlow[Any, Nothing, ExecutionEnvironment] {
    val errorSchema  = Schema[ZNothing]
    val resultSchema = Schema.fail[ExecutionEnvironment]("not serializable")

    val schema: Schema[GetExecutionEnvironment.type] = Schema.singleton(GetExecutionEnvironment)
    def schemaCase[R, E, A]: Schema.Case[GetExecutionEnvironment.type, ZFlow[R, E, A]] =
      Schema.Case("GetExecutionEnvironment", schema, _.asInstanceOf[GetExecutionEnvironment.type])
  }

  def apply[A: Schema](a: A): ZFlow[Any, ZNothing, A] = Return(Remote(a))

  def apply[A](remote: Remote[A]): ZFlow[Any, ZNothing, A] = Return(remote)

  def doUntil[R, E: Schema](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(false).iterate((_: Remote[Boolean]) => flow)(_ === false)

  def doWhile[R, E: Schema](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(true).iterate((_: Remote[Boolean]) => flow)(_ === true)

  def foreach[R, E: Schema, A: Schema, B: Schema](
    values: Remote[List[A]]
  )(body: Remote[A] => ZFlow[R, E, B]): ZFlow[R, E, List[B]] =
    ZFlow.unwrap {
      values.fold[ZFlow[R, E, List[B]]](ZFlow.succeed(Remote(Nil))) { (bs, a) =>
        for {
          bs <- ZFlow.unwrap(bs)
          b  <- body(a)
        } yield Remote.Cons(bs, b)
      }
    }.map(_.reverse)

  def foreachPar[R, A: Schema, B: Schema](
    values: Remote[List[A]]
  )(body: Remote[A] => ZFlow[R, ActivityError, B]): ZFlow[R, ActivityError, List[B]] =
    for {
      executingFlows <- ZFlow.foreach[R, ZNothing, A, ExecutingFlow[ActivityError, B]](values)((remoteA: Remote[A]) =>
                          body(remoteA).fork
                        )
      eithers <- ZFlow.foreach(executingFlows)(remote => ZFlow.Await(remote))
      bs      <- ZFlow.fromEither(remote.RemoteEitherSyntax.collectAll(eithers))
    } yield bs

  def ifThenElse[R, E: Schema, A: Schema](
    p: Remote[Boolean]
  )(ifTrue: ZFlow[R, E, A], ifFalse: ZFlow[R, E, A]): ZFlow[R, E, A] =
    ZFlow.unwrap(p.ifThenElse(ifTrue, ifFalse))

  def input[R: Schema]: ZFlow[R, ZNothing, R] = Input[R]()

  def newVar[A](name: String, initial: Remote[A]): ZFlow[Any, ZNothing, RemoteVariableReference[A]] =
    NewVar(name, initial)

  def now: ZFlow[Any, ZNothing, Instant] = Now

  def sleep(duration: Remote[Duration]): ZFlow[Any, ZNothing, Unit] =
    // TODO: why are these type args needed - they prevent using for comprehension
    ZFlow.now.flatMap[Any, ZNothing, Unit] { now =>
      ZFlow(now.plusDuration(duration)).flatMap[Any, ZNothing, Unit] { later =>
        ZFlow.waitTill(later).map { _ =>
          Remote.unit
        }
      }
    }

  def transaction[R, E: Schema, A: Schema](
    make: ZFlowTransaction => ZFlow[R, E, A]
  ): ZFlow[R, E, A] =
    Transaction(make(ZFlowTransaction.instance))

  val unit: ZFlow[Any, ZNothing, Unit] = ZFlow(Remote.unit)

  def unwrap[R, E: Schema, A: Schema](remote: Remote[ZFlow[R, E, A]]): ZFlow[R, E, A] =
    Unwrap(remote)

  def unwrapRemote[A: Schema](remote: Remote[Remote[A]]): ZFlow[Any, ZNothing, A] =
    UnwrapRemote(remote)

  def waitTill(instant: Remote[Instant]): ZFlow[Any, ZNothing, Unit] = WaitTill(instant)

  def when[R, E: Schema, A: Schema](predicate: Remote[Boolean])(
    flow: ZFlow[R, E, A]
  ): ZFlow[R, E, Unit] =
    ZFlow.ifThenElse[R, E, Unit](predicate)(flow.unit, ZFlow.unit)

  def fail[E: Schema](error: Remote[E]): ZFlow[Any, E, ZNothing] = ZFlow.Fail(error)

  def succeed[A](value: Remote[A]): ZFlow[Any, ZNothing, A] = ZFlow.Return(value)

  def fromEither[E: Schema, A: Schema](either: Remote[Either[E, A]]): ZFlow[Any, E, A] =
    ZFlow.unwrap(either.handleEither((e: Remote[E]) => ZFlow.fail(e), (a: Remote[A]) => ZFlow.succeed(a)))

  def log(message: String): ZFlow[Any, ZNothing, Unit] = ZFlow.Log(Remote(message))

  def log(remoteMessage: Remote[String]): ZFlow[Any, ZNothing, Unit] = ZFlow.Log(remoteMessage)

  def iterate[R, E, A](
    initial: Remote[A],
    step: Remote[A] => Remote[ZFlow[R, E, A]],
    predicate: Remote[A] => Remote[Boolean]
  )(implicit
    errorSchema: Schema[E],
    resultSchema: Schema[A]
  ): ZFlow.Iterate[R, E, A] =
    ZFlow.Iterate(initial, RemoteFunction(step).evaluated, RemoteFunction(predicate).evaluated)

  val executionEnvironment: ZFlow[Any, Nothing, ExecutionEnvironment] = ZFlow.GetExecutionEnvironment

  private def createSchema[R, E, A]: Schema[ZFlow[R, E, A]] =
    Schema.EnumN(
      CaseSet
        .Cons(Return.schemaCase[R, E, A], CaseSet.Empty[ZFlow[R, E, A]]())
        .:+:(Now.schemaCase[R, E, A])
        .:+:(WaitTill.schemaCase[R, E, A])
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
        .:+:(GetExecutionEnvironment.schemaCase[R, E, A])
    )

  lazy val schemaAny: Schema[ZFlow[Any, Any, Any]]     = createSchema[Any, Any, Any]
  implicit def schema[R, E, A]: Schema[ZFlow[R, E, A]] = schemaAny.asInstanceOf[Schema[ZFlow[R, E, A]]]
}
