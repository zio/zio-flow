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
import zio.stream.ZNothing

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

  val errorSchema: SchemaOrNothing.Aux[_ <: E]
  val resultSchema: SchemaOrNothing.Aux[_ <: A]

  final def *>[R1 <: R, E1 >: E: SchemaOrNothing.Aux, A1 >: A: SchemaOrNothing.Aux, B: SchemaOrNothing.Aux](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    self.zip[R1, E1, A1, B](that).map((a: Remote[(A1, B)]) => a._2)

  final def <*[R1 <: R, E1 >: E: SchemaOrNothing.Aux, A1 >: A: SchemaOrNothing.Aux, B: SchemaOrNothing.Aux](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, A1] =
    self.zip[R1, E1, A1, B](that).map((a: Remote[(A1, B)]) => a._1)

  final def as[B: SchemaOrNothing.Aux](b: => Remote[B]): ZFlow[R, E, B] =
    self.map[B](_ => b)

  final def catchAll[R1 <: R, E1 >: E, A1 >: A: SchemaOrNothing.Aux, E2: SchemaOrNothing.Aux](
    f: Remote[E1] => ZFlow[R1, E2, A1]
  ): ZFlow[R1, E2, A1] =
    self.foldM(f, (a: Remote[A1]) => ZFlow(a))

  final def ensuring[R1 <: R: SchemaOrNothing.Aux, E1 >: E: SchemaOrNothing.Aux, A1 >: A: SchemaOrNothing.Aux](
    flow: ZFlow[R1, Nothing, Any]
  ): ZFlow[R1, E1, A1] =
    ZFlow
      .input[R1]
      .flatMap[R1, E1, A1]((r: Remote[R1]) => ZFlow.Ensuring(self, flow.unit.provide(r)))

  final def ensuring[E1 >: E: SchemaOrNothing.Aux, A1 >: A: SchemaOrNothing.Aux](
    flow: ZFlow[Any, Nothing, Any]
  ): ZFlow[R, E1, A1] =
    ZFlow.Ensuring[R, E1, A1](self, flow.unit)

  final def flatMap[R1 <: R, E1 >: E: SchemaOrNothing.Aux, B: SchemaOrNothing.Aux](
    f: Remote[A] => ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, B] =
    foldM(
      (e: Remote[E1]) => ZFlow.Fail(e),
      f
    )

  final def foldM[R1 <: R, E2: SchemaOrNothing.Aux, B: SchemaOrNothing.Aux](
    error: Remote[E] => ZFlow[R1, E2, B],
    success: Remote[A] => ZFlow[R1, E2, B]
  ): ZFlow[R1, E2, B] =
    ZFlow.Fold(
      self,
      RemoteFunction(error.andThen(_.toRemote))(
        errorSchema.asInstanceOf[SchemaOrNothing.Aux[E]]
      ).evaluated,
      RemoteFunction(success.andThen(_.toRemote))(
        resultSchema.asInstanceOf[SchemaOrNothing.Aux[A]]
      ).evaluated
    )

  final def fork[E1 >: E: SchemaOrNothing.Aux, A1 >: A: SchemaOrNothing.Aux]: ZFlow[R, Nothing, ExecutingFlow[E1, A1]] =
    ZFlow.Fork[R, E1, A1](self)

  final def ifThenElse[R1 <: R, E1 >: E: SchemaOrNothing.Aux, B: SchemaOrNothing.Aux](
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
  )(implicit schemaA: SchemaOrNothing.Aux[A1], schemaE: SchemaOrNothing.Aux[E1]): ZFlow[R1, E1, A1] =
    self.flatMap((remoteA: Remote[A1]) =>
      ZFlow.Iterate(
        remoteA,
        RemoteFunction((a1: Remote[A1]) => step(a1).toRemote)(schemaA).evaluated,
        RemoteFunction(predicate)(schemaA).evaluated
      )
    )

  final def map[B: SchemaOrNothing.Aux](f: Remote[A] => Remote[B]): ZFlow[R, E, B] =
    self.flatMap[R, E, B](a => ZFlow(f(a)))(errorSchema.asInstanceOf[SchemaOrNothing.Aux[E]], SchemaOrNothing[B])

  final def orDie[R1 <: R, E1 >: E, A1 >: A: SchemaOrNothing.Aux]: ZFlow[R1, ZNothing, A1] =
    self.catchAll[R1, E1, A1, ZNothing]((_: Remote[E1]) => ZFlow.Die)

  final def orElse[R1 <: R, E1 >: E, E2: SchemaOrNothing.Aux, A1 >: A: SchemaOrNothing.Aux](
    that: ZFlow[R1, E2, A1]
  ): ZFlow[R1, E2, A1] =
    self.catchAll((_: Remote[E1]) => that)

  final def orElseEither[R1 <: R, A1 >: A, E2: SchemaOrNothing.Aux, B](
    that: ZFlow[R1, E2, B]
  )(implicit schemaA: SchemaOrNothing.Aux[A1], schemaB: SchemaOrNothing.Aux[B]): ZFlow[R1, E2, Either[A1, B]] = {
    implicit val sa: Schema[A1] = schemaA.schema
    implicit val sb: Schema[B]  = schemaB.schema
    self
      .map[Either[A1, B]](a => Remote.sequenceEither[A1, B](Left(a)))
      .catchAll((_: Remote[E]) => that.map((b: Remote[B]) => Remote.sequenceEither[A1, B](Right(b))))
  }

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

  final def timeout[E1 >: E: SchemaOrNothing.Aux, A1 >: A: SchemaOrNothing.Aux](
    duration: Remote[Duration]
  ): ZFlow[R, E1, Option[A1]] =
    ZFlow.Timeout[R, E1, A1](self, duration)

  final def unit: ZFlow[R, E, Unit] = as(())

  final def zip[R1 <: R, E1 >: E: SchemaOrNothing.Aux, A1 >: A: SchemaOrNothing.Aux, B: SchemaOrNothing.Aux](
    that: ZFlow[R1, E1, B]
  ): ZFlow[R1, E1, (A1, B)] = {
    implicit val a1Schema = SchemaOrNothing[A1].schema
    implicit val bSchema  = SchemaOrNothing[B].schema
    self.flatMap((a: Remote[A1]) => that.map((b: Remote[B]) => a -> b))
  }

  final def widen[A0](implicit ev: A <:< A0): ZFlow[R, E, A0] = {
    val _ = ev

    self.asInstanceOf[ZFlow[R, E, A0]]
  }
}

object ZFlow {

  final case class Return[A](value: Remote[A]) extends ZFlow[Any, Nothing, A] {
    val errorSchema  = SchemaOrNothing.nothing
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
    val errorSchema                                = SchemaOrNothing.nothing
    val resultSchema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing.fromSchema[Instant]

    val schema: Schema[Now.type] = Schema.singleton(Now)
    def schemaCase[R, E, A]: Schema.Case[Now.type, ZFlow[R, E, A]] =
      Schema.Case("Now", schema, _.asInstanceOf[Now.type])
  }

  final case class WaitTill(time: Remote[Instant]) extends ZFlow[Any, Nothing, Unit] {
    val errorSchema                             = SchemaOrNothing.nothing
    val resultSchema: SchemaOrNothing.Aux[Unit] = SchemaOrNothing.fromSchema[Unit]
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

  final case class Modify[A, B](svar: Remote[Remote.Variable[A]], f: EvaluatedRemoteFunction[A, (B, A)])(implicit
    val resultSchema: SchemaOrNothing.Aux[B]
  ) extends ZFlow[Any, Nothing, B] {
    val errorSchema = SchemaOrNothing.nothing
  }

  object Modify {
    def schema[A, B]: Schema[Modify[A, B]] =
      Schema.CaseClass3[Remote[Remote.Variable[A]], EvaluatedRemoteFunction[A, (B, A)], FlowSchemaAst, Modify[A, B]](
        Schema.Field("svar", Remote.schema[Remote.Variable[A]]), // TODO: eliminate the need of recursive remote
        Schema.Field("f", EvaluatedRemoteFunction.schema[A, (B, A)]),
        Schema.Field("resultSchema", FlowSchemaAst.schema),
        { case (svar, f, schemaAst) =>
          Modify(svar, f)(SchemaOrNothing.fromSchema(schemaAst.toSchema[B]))
        },
        _.svar,
        _.f,
        flow => FlowSchemaAst.fromSchema(flow.resultSchema.schema)
      )

    def schemaCase[R, E, A]: Schema.Case[Modify[Any, Any], ZFlow[R, E, A]] =
      Schema.Case("Modify", schema[Any, Any], _.asInstanceOf[Modify[Any, Any]])
  }

  final case class Fold[R, E, E2, A, B](
    value: ZFlow[R, E, A],
    ifError: EvaluatedRemoteFunction[E, ZFlow[R, E2, B]],
    ifSuccess: EvaluatedRemoteFunction[A, ZFlow[R, E2, B]]
  )(implicit
    val errorSchema: SchemaOrNothing.Aux[E2],
    val resultSchema: SchemaOrNothing.Aux[B]
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
              SchemaOrNothing.fromSchema(errorSchema.toSchema[E2]),
              SchemaOrNothing.fromSchema(resultSchema.toSchema[B])
            )
          },
          _.value,
          _.ifError,
          _.ifSuccess,
          flow => FlowSchemaAst.fromSchema(flow.errorSchema.schema),
          flow => FlowSchemaAst.fromSchema(flow.resultSchema.schema)
        )
      )

    def schemaCase[R, E, A]: Schema.Case[ZFlow.Fold[Any, Any, Any, Any, Any], ZFlow[R, E, A]] =
      Schema.Case("Fold", schema[Any, Any, Any, Any, Any], _.asInstanceOf[Fold[Any, Any, Any, Any, Any]])
  }

  // TODO: not used for anything?
  final case class Apply[A, E, B](lambda: EvaluatedRemoteFunction[A, ZFlow[Any, E, B]])(implicit
    val errorSchema: SchemaOrNothing.Aux[E],
    val resultSchema: SchemaOrNothing.Aux[B]
  ) extends ZFlow[A, E, B] {
    type ValueE = E
    type ValueA = A
    type ValueB = B
  }

  object Apply {
    def schema[A, E, B]: Schema[Apply[A, E, B]] =
      Schema.defer(
        Schema.CaseClass3[EvaluatedRemoteFunction[A, ZFlow[Any, E, B]], FlowSchemaAst, FlowSchemaAst, Apply[A, E, B]](
          Schema.Field("lambda", EvaluatedRemoteFunction.schema[A, ZFlow[Any, E, B]]),
          Schema.Field("errorSchema", FlowSchemaAst.schema),
          Schema.Field("resultSchema", FlowSchemaAst.schema),
          { case (lambda, errorSchema, resultSchema) =>
            Apply(lambda)(
              SchemaOrNothing.fromSchema(errorSchema.toSchema[E]),
              SchemaOrNothing.fromSchema(resultSchema.toSchema[B])
            )
          },
          _.lambda,
          flow => FlowSchemaAst.fromSchema(flow.errorSchema.schema),
          flow => FlowSchemaAst.fromSchema(flow.resultSchema.schema)
        )
      )

    def schemaCase[R, E, A]: Schema.Case[ZFlow.Apply[Any, E, A], ZFlow[R, E, A]] =
      Schema.Case("Apply", schema[Any, E, A], _.asInstanceOf[Apply[Any, E, A]])
  }

  final case class Log(message: String) extends ZFlow[Any, Nothing, Unit] {
    val errorSchema                             = SchemaOrNothing.nothing
    val resultSchema: SchemaOrNothing.Aux[Unit] = SchemaOrNothing.fromSchema[Unit]
  }

  object Log {
    val schema: Schema[Log] =
      Schema[String].transform(Log(_), _.message)

    def schemaCase[R, E, A]: Schema.Case[ZFlow.Log, ZFlow[R, E, A]] =
      Schema.Case("Log", schema, _.asInstanceOf[Log])
  }

  final case class RunActivity[R, A](input: Remote[R], activity: Activity[R, A]) extends ZFlow[Any, ActivityError, A] {
    val errorSchema  = SchemaOrNothing.fromSchema[ActivityError]
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

  final case class Input[R]()(implicit val resultSchema: SchemaOrNothing.Aux[R]) extends ZFlow[R, Nothing, R] {
    val errorSchema = SchemaOrNothing.nothing
  }

  object Input {
    def schema[R]: Schema[Input[R]] =
      FlowSchemaAst.schema.transform(
        ast => Input()(SchemaOrNothing.fromSchema(ast.toSchema[R])),
        flow => FlowSchemaAst.fromSchema(flow.resultSchema.schema)
      )

    def schemaCase[R, E, A]: Schema.Case[Input[R], ZFlow[R, E, A]] =
      Schema.Case("Input", schema[R], _.asInstanceOf[Input[R]])
  }

  final case class Ensuring[R, E, A](flow: ZFlow[R, E, A], finalizer: ZFlow[Any, Nothing, Unit])
      extends ZFlow[R, E, A] {
    type ValueR = R
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
    val errorSchema: SchemaOrNothing.Aux[E],
    val resultSchema: SchemaOrNothing.Aux[A]
  ) extends ZFlow[R, E, A]

  object Unwrap {
    def schema[R, E, A]: Schema[Unwrap[R, E, A]] =
      Schema.CaseClass3[Remote[ZFlow[R, E, A]], FlowSchemaAst, FlowSchemaAst, Unwrap[R, E, A]](
        Schema.Field("remote", Remote.schema[ZFlow[R, E, A]]),
        Schema.Field("errorSchema", FlowSchemaAst.schema),
        Schema.Field("resultSchema", FlowSchemaAst.schema),
        { case (remote, errorSchemaAst, resultSchemaAst) =>
          Unwrap(remote)(
            SchemaOrNothing.fromSchema(errorSchemaAst.toSchema[E]),
            SchemaOrNothing.fromSchema(resultSchemaAst.toSchema[A])
          )
        },
        _.remote,
        flow => FlowSchemaAst.fromSchema(flow.errorSchema.schema),
        flow => FlowSchemaAst.fromSchema(flow.resultSchema.schema)
      )

    def schemaCase[R, E, A]: Schema.Case[Unwrap[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Unwrap", schema[R, E, A], _.asInstanceOf[Unwrap[R, E, A]])
  }

  final case class UnwrapRemote[A](remote: Remote[Remote[A]])(implicit
    val resultSchema: SchemaOrNothing.Aux[A]
  ) extends ZFlow[Any, Nothing, A] {
    val errorSchema = SchemaOrNothing.nothing
  }

  object UnwrapRemote {
    def schema[A]: Schema[UnwrapRemote[A]] =
      Schema.CaseClass2[Remote[Remote[A]], FlowSchemaAst, UnwrapRemote[A]](
        Schema.Field("remote", Remote.schema[Remote[A]]),
        Schema.Field("resultSchema", FlowSchemaAst.schema),
        { case (remote, resultSchemaAst) =>
          UnwrapRemote(remote)(
            SchemaOrNothing.fromSchema(resultSchemaAst.toSchema[A])
          )
        },
        _.remote,
        flow => FlowSchemaAst.fromSchema(flow.resultSchema.schema)
      )

    def schemaCase[R, E, A]: Schema.Case[UnwrapRemote[A], ZFlow[R, E, A]] =
      Schema.Case("UnwrapRemote", schema[A], _.asInstanceOf[UnwrapRemote[A]])
  }

  final case class Fork[R, E, A](flow: ZFlow[R, E, A])(implicit
    schemaOrNE: SchemaOrNothing.Aux[E],
    schemaOrNA: SchemaOrNothing.Aux[A]
  ) extends ZFlow[R, Nothing, ExecutingFlow[E, A]] {
    type ValueE = E
    type ValueA = A
    val schemaE: Schema[E] = schemaOrNE.schema
    val schemaA: Schema[A] = schemaOrNA.schema
    val errorSchema        = SchemaOrNothing.nothing
    val resultSchema: SchemaOrNothing.Aux[ExecutingFlow[E, A]] =
      SchemaOrNothing.fromSchema[ExecutingFlow[E, A]](ExecutingFlow.schema)
  }

  object Fork {
    def schema[R, E, A]: Schema[Fork[R, E, A]] =
      Schema.defer(
        Schema.CaseClass3[ZFlow[R, E, A], FlowSchemaAst, FlowSchemaAst, Fork[R, E, A]](
          Schema.Field("flow", ZFlow.schema[R, E, A]),
          Schema.Field("schemaE", FlowSchemaAst.schema),
          Schema.Field("schemaA", FlowSchemaAst.schema),
          { case (workflow, schemaAstE, schemaAstA) =>
            Fork(workflow)(
              SchemaOrNothing.fromSchema(schemaAstE.toSchema[E]),
              SchemaOrNothing.fromSchema(schemaAstA.toSchema[A])
            )
          },
          _.flow,
          flow => FlowSchemaAst.fromSchema(flow.schemaE),
          flow => FlowSchemaAst.fromSchema(flow.schemaA)
        )
      )

    def schemaCase[R, E, A]: Schema.Case[Fork[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Fork", schema[R, E, A], _.asInstanceOf[Fork[R, E, A]])
  }

  final case class Timeout[R, E, A](flow: ZFlow[R, E, A], duration: Remote[Duration])(implicit
    val schemaOrNE: SchemaOrNothing.Aux[E],
    val schemaOrNA: SchemaOrNothing.Aux[A]
  ) extends ZFlow[R, E, Option[A]] {
    type ValueA = A
    type ValueE = E
    implicit val schemaE: Schema[E]                  = schemaOrNE.schema
    implicit val schemaA: Schema[A]                  = schemaOrNA.schema
    val schemaEitherE: Schema[Either[Throwable, E]]  = Schema[Either[Throwable, E]]
    val errorSchema                                  = schemaOrNE
    val resultSchema: SchemaOrNothing.Aux[Option[A]] = SchemaOrNothing.fromSchema[Option[A]]
  }

  object Timeout {
    def schema[R, E, A]: Schema[Timeout[R, E, A]] =
      Schema.defer(
        Schema.CaseClass4[ZFlow[R, E, A], Remote[Duration], FlowSchemaAst, FlowSchemaAst, Timeout[R, E, A]](
          Schema.Field("flow", ZFlow.schema[R, E, A]),
          Schema.Field("duration", Remote.schema[Duration]),
          Schema.Field("schemaE", FlowSchemaAst.schema),
          Schema.Field("schemaA", FlowSchemaAst.schema),
          { case (workflow, duration, schemaAstE, schemaAstA) =>
            Timeout(workflow, duration)(
              SchemaOrNothing.fromSchema(schemaAstE.toSchema[E]),
              SchemaOrNothing.fromSchema(schemaAstA.toSchema[A])
            )
          },
          _.flow,
          _.duration,
          flow => FlowSchemaAst.fromSchema(flow.schemaE),
          flow => FlowSchemaAst.fromSchema(flow.schemaA)
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
    val errorSchema  = SchemaOrNothing.nothing
    val resultSchema = SchemaOrNothing.nothing

    val schema: Schema[Die.type] = Schema.singleton(Die)
    def schemaCase[R, E, A]: Schema.Case[Die.type, ZFlow[R, E, A]] =
      Schema.Case("Die", schema, _.asInstanceOf[Die.type])
  }

  case object RetryUntil extends ZFlow[Any, Nothing, Nothing] {
    val errorSchema  = SchemaOrNothing.nothing
    val resultSchema = SchemaOrNothing.nothing

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
    val schemaOrNE: SchemaOrNothing.Aux[E],
    val schemaOrNA: SchemaOrNothing.Aux[A]
  ) extends ZFlow[Any, ActivityError, Either[E, A]] {
    type ValueE = E
    type ValueA = A
    implicit val schemaE: Schema[E]                 = schemaOrNE.schema
    val schemaEitherE: Schema[Either[Throwable, E]] = Schema[Either[Throwable, E]]
    implicit val schemaA: Schema[A]                 = schemaOrNA.schema
    val errorSchema                                 = SchemaOrNothing.fromSchema[ActivityError]
    val resultSchema                                = SchemaOrNothing.fromSchema[Either[E, A]]
  }

  object Await {
    def schema[E, A]: Schema[Await[E, A]] =
      Schema.CaseClass3[Remote[ExecutingFlow[E, A]], FlowSchemaAst, FlowSchemaAst, Await[E, A]](
        Schema.Field("exFlow", Remote.schema[ExecutingFlow[E, A]]),
        Schema.Field("schemaE", FlowSchemaAst.schema),
        Schema.Field("schemaA", FlowSchemaAst.schema),
        { case (exFlow, schemaAstE, schemaAstA) =>
          Await(exFlow)(
            SchemaOrNothing.fromSchema(schemaAstE.toSchema[E]),
            SchemaOrNothing.fromSchema(schemaAstA.toSchema[A])
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
    val errorSchema  = SchemaOrNothing.fromSchema[ActivityError]
    val resultSchema = SchemaOrNothing.fromSchema[Unit]
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
    override val resultSchema = SchemaOrNothing.nothing
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

  final case class NewVar[A](name: String, initial: Remote[A]) extends ZFlow[Any, Nothing, Remote.Variable[A]] {
    val errorSchema  = SchemaOrNothing.nothing
    val resultSchema = SchemaOrNothing.fromSchema(Remote.Variable.schema[A])
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
    val errorSchema: SchemaOrNothing.Aux[E],
    val resultSchema: SchemaOrNothing.Aux[A]
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
              SchemaOrNothing.fromSchema(errorSchema.toSchema[E]),
              SchemaOrNothing.fromSchema(resultSchema.toSchema[A])
            )
          },
          _.initial,
          _.step,
          _.predicate,
          flow => FlowSchemaAst.fromSchema(flow.errorSchema.schema),
          flow => FlowSchemaAst.fromSchema(flow.resultSchema.schema)
        )
      )

    def schemaCase[R, E, A]: Schema.Case[Iterate[R, E, A], ZFlow[R, E, A]] =
      Schema.Case("Iterate", schema[R, E, A], _.asInstanceOf[Iterate[R, E, A]])
  }

  case object GetExecutionEnvironment extends ZFlow[Any, Nothing, ExecutionEnvironment] {
    val errorSchema  = SchemaOrNothing.nothing
    val resultSchema = SchemaOrNothing.fromSchema(Schema.fail[ExecutionEnvironment]("not serializable"))

    val schema: Schema[GetExecutionEnvironment.type] = Schema.singleton(GetExecutionEnvironment)
    def schemaCase[R, E, A]: Schema.Case[GetExecutionEnvironment.type, ZFlow[R, E, A]] =
      Schema.Case("GetExecutionEnvironment", schema, _.asInstanceOf[GetExecutionEnvironment.type])
  }

  def apply[A: Schema](a: A): ZFlow[Any, Nothing, A] = Return(Remote(a))

  def apply[A](remote: Remote[A]): ZFlow[Any, Nothing, A] = Return(remote)

  def doUntil[R, E: SchemaOrNothing.Aux](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(false).iterate((_: Remote[Boolean]) => flow)(_ === false)

  def doWhile[R, E: SchemaOrNothing.Aux](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(true).iterate((_: Remote[Boolean]) => flow)(_ === true)

  def foreach[R, E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux, B: SchemaOrNothing.Aux](
    values: Remote[List[A]]
  )(body: Remote[A] => ZFlow[R, E, B]): ZFlow[R, E, List[B]] = {
    implicit val schemaA: Schema[A] = SchemaOrNothing[A].schema
    implicit val schemaB: Schema[B] = SchemaOrNothing[B].schema
    ZFlow.unwrap {
      values.fold[ZFlow[R, E, List[B]]](ZFlow.succeed(Remote(Nil))) { (bs, a) =>
        for {
          bs <- ZFlow.unwrap(bs)
          b  <- body(a)
        } yield Remote.Cons(bs, b)
      }
    }.map(_.reverse)
  }

  def foreachPar[R, A: SchemaOrNothing.Aux, B: SchemaOrNothing.Aux](
    values: Remote[List[A]]
  )(body: Remote[A] => ZFlow[R, ActivityError, B]): ZFlow[R, ActivityError, List[B]] = {
    implicit val schemaB: Schema[B] = SchemaOrNothing[B].schema
    for {
      executingFlows <- ZFlow.foreach[R, ZNothing, A, ExecutingFlow[ActivityError, B]](values)((remoteA: Remote[A]) =>
                          body(remoteA).fork
                        )
      eithers <- ZFlow.foreach(executingFlows)(remote => ZFlow.Await(remote))
      bs      <- ZFlow.fromEither(remote.RemoteEitherSyntax.collectAll(eithers))
    } yield bs
  }

  def ifThenElse[R, E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](
    p: Remote[Boolean]
  )(ifTrue: ZFlow[R, E, A], ifFalse: ZFlow[R, E, A]): ZFlow[R, E, A] =
    ZFlow.unwrap(p.ifThenElse(ifTrue, ifFalse))

  def input[R: SchemaOrNothing.Aux]: ZFlow[R, Nothing, R] = Input[R]()

  def newVar[A](name: String, initial: Remote[A]): ZFlow[Any, Nothing, Remote.Variable[A]] =
    NewVar(name, initial)

  def now: ZFlow[Any, Nothing, Instant] = Now

  def sleep(duration: Remote[Duration]): ZFlow[Any, Nothing, Unit] =
    // TODO: why are these type args needed - they prevent using for comprehension
    ZFlow.now.flatMap[Any, ZNothing, Unit] { now =>
      ZFlow(now.plusDuration(duration)).flatMap[Any, ZNothing, Unit] { later =>
        ZFlow.waitTill(later).map { _ =>
          Remote.unit
        }
      }
    }

  def transaction[R, E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](
    make: ZFlowTransaction => ZFlow[R, E, A]
  ): ZFlow[R, E, A] =
    Transaction(make(ZFlowTransaction.instance))

  val unit: ZFlow[Any, Nothing, Unit] = ZFlow(Remote.unit)

  def unwrap[R, E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](remote: Remote[ZFlow[R, E, A]]): ZFlow[R, E, A] =
    Unwrap(remote)

  def unwrapRemote[A: SchemaOrNothing.Aux](remote: Remote[Remote[A]]): ZFlow[Any, Nothing, A] =
    UnwrapRemote(remote)

  def waitTill(instant: Remote[Instant]): ZFlow[Any, Nothing, Unit] = WaitTill(instant)

  def when[R, E: SchemaOrNothing.Aux, A: SchemaOrNothing.Aux](predicate: Remote[Boolean])(
    flow: ZFlow[R, E, A]
  ): ZFlow[R, E, Unit] =
    ZFlow.ifThenElse[R, E, Unit](predicate)(flow.unit, ZFlow.unit)

  def fail[E: SchemaOrNothing.Aux](error: Remote[E]): ZFlow[Any, E, Nothing] = ZFlow.Fail(error)

  def succeed[A](value: Remote[A]): ZFlow[Any, Nothing, A] = ZFlow.Return(value)

  def fromEither[E: Schema, A: Schema](either: Remote[Either[E, A]]): ZFlow[Any, E, A] =
    ZFlow.unwrap(either.handleEither((e: Remote[E]) => ZFlow.fail(e), (a: Remote[A]) => ZFlow.succeed(a)))

  def log(message: String): ZFlow[Any, Nothing, Unit] = ZFlow.Log(message)

  def iterate[R, E, A](
    initial: Remote[A],
    step: Remote[A] => Remote[ZFlow[R, E, A]],
    predicate: Remote[A] => Remote[Boolean]
  )(implicit
    errorSchema: SchemaOrNothing.Aux[E],
    resultSchema: SchemaOrNothing.Aux[A]
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
        .:+:(Apply.schemaCase[R, E, A])
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
