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

import zio.NeedsEnv
import zio.flow.Remote._
import zio.schema.{CaseSet, Schema}

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
  )(implicit ev: NeedsEnv[R1]): ZFlow[R1, E1, A1] =
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
      RemoteFunction(error.andThen(Remote[ZFlow[R1, E2, B]]))(errorSchema.asInstanceOf[SchemaOrNothing.Aux[E]]),
      RemoteFunction(success.andThen(Remote[ZFlow[R1, E2, B]]))(resultSchema.asInstanceOf[SchemaOrNothing.Aux[A]])
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
      .flatMap((bool: Remote[Boolean]) => ZFlow.unwrap(bool.ifThenElse(Remote(ifTrue), Remote(ifFalse))))

  final def iterate[R1 <: R, E1 >: E, A1 >: A](step: Remote[A1] => ZFlow[R1, E1, A1])(
    predicate: Remote[A1] => Remote[Boolean]
  )(implicit schemaA: SchemaOrNothing.Aux[A1], schemaE: SchemaOrNothing.Aux[E1]): ZFlow[R1, E1, A1] =
    self.flatMap((remoteA: Remote[A1]) =>
      ZFlow.Iterate(
        remoteA,
        RemoteFunction((a1: Remote[A1]) => Remote(step(a1)))(schemaA),
        RemoteFunction(predicate)(schemaA)
      )
    )

  final def map[B: SchemaOrNothing.Aux](f: Remote[A] => Remote[B]): ZFlow[R, E, B] =
    self.flatMap[R, E, B](a => ZFlow(f(a)))(errorSchema.asInstanceOf[SchemaOrNothing.Aux[E]], SchemaOrNothing[B])

  final def orDie[R1 <: R, E1 >: E, A1 >: A: SchemaOrNothing.Aux]: ZFlow[R1, Nothing, A1] =
    self.catchAll[R1, E1, A1, Nothing]((_: Remote[E1]) => ZFlow.Die)

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

  final case class Modify[A, B](svar: Remote[Remote.Variable[A]], f: A ===> (B, A))(implicit
    val resultSchema: SchemaOrNothing.Aux[B]
  ) extends ZFlow[Any, Nothing, B] {
    val errorSchema = SchemaOrNothing.nothing
  }

  final case class Fold[R, E, E2, A, B](
    value: ZFlow[R, E, A],
    ifError: RemoteFunction[E, ZFlow[R, E2, B]],
    ifSuccess: RemoteFunction[A, ZFlow[R, E2, B]]
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

  final case class Apply[A, E: Schema, B: Schema](lambda: A ===> ZFlow[Any, E, B])(implicit
    val errorSchema: SchemaOrNothing.Aux[E],
    val resultSchema: SchemaOrNothing.Aux[B]
  ) extends ZFlow[A, E, B] {
    type ValueE = E
    type ValueA = A
    type ValueB = B
  }

  final case class Log(message: String) extends ZFlow[Any, Nothing, Unit] {
    val errorSchema                             = SchemaOrNothing.nothing
    val resultSchema: SchemaOrNothing.Aux[Unit] = SchemaOrNothing.fromSchema[Unit]
  }

  final case class RunActivity[R, A](input: Remote[R], activity: Activity[R, A])(implicit
    val resultSchema: SchemaOrNothing.Aux[A]
  ) extends ZFlow[Any, ActivityError, A] {
    val errorSchema = SchemaOrNothing.fromSchema[ActivityError]
  }

  final case class Transaction[R, E, A](workflow: ZFlow[R, E, A])(implicit
    val errorSchema: SchemaOrNothing.Aux[E],
    val resultSchema: SchemaOrNothing.Aux[A]
  ) extends ZFlow[R, E, A] {
    type ValueR = R
  }

  final case class Input[R]()(implicit val resultSchema: SchemaOrNothing.Aux[R]) extends ZFlow[R, Nothing, R] {
    val errorSchema = SchemaOrNothing.nothing
  }

  final case class Ensuring[R, E, A](flow: ZFlow[R, E, A], finalizer: ZFlow[Any, Nothing, Unit])
      extends ZFlow[R, E, A] {
    type ValueR = R
    type ValueE = E
    type ValueA = A

    val errorSchema  = flow.errorSchema
    val resultSchema = flow.resultSchema
  }

  final case class Unwrap[R, E, A](remote: Remote[ZFlow[R, E, A]])(implicit
    val errorSchema: SchemaOrNothing.Aux[E],
    val resultSchema: SchemaOrNothing.Aux[A]
  ) extends ZFlow[R, E, A]

  final case class UnwrapRemote[A](remote: Remote[Remote[A]])(implicit
    val resultSchema: SchemaOrNothing.Aux[A]
  ) extends ZFlow[Any, Nothing, A] {
    val errorSchema = SchemaOrNothing.nothing
  }

  final case class Fork[R, E, A](workflow: ZFlow[R, E, A])(implicit
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

  final case class Provide[R, E, A](value: Remote[R], flow: ZFlow[R, E, A]) extends ZFlow[Any, E, A] {
    type ValueE = E
    type ValueA = A
    val errorSchema  = flow.errorSchema
    val resultSchema = flow.resultSchema
  }

  case object Die extends ZFlow[Any, Nothing, Nothing] {
    val errorSchema  = SchemaOrNothing.nothing
    val resultSchema = SchemaOrNothing.nothing
  }

  case object RetryUntil extends ZFlow[Any, Nothing, Nothing] {
    val errorSchema  = SchemaOrNothing.nothing
    val resultSchema = SchemaOrNothing.nothing
  }

  final case class OrTry[R, E, A](left: ZFlow[R, E, A], right: ZFlow[R, E, A]) extends ZFlow[R, E, A] {
    val errorSchema  = left.errorSchema
    val resultSchema = left.resultSchema
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

  final case class Interrupt[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Unit] {
    val errorSchema  = SchemaOrNothing.fromSchema[ActivityError]
    val resultSchema = SchemaOrNothing.fromSchema[Unit]
  }

  final case class Fail[E](error: Remote[E])(implicit val errorSchema: SchemaOrNothing.Aux[E])
      extends ZFlow[Any, E, Nothing] {
    val resultSchema = SchemaOrNothing.nothing
  }

  final case class NewVar[A](name: String, initial: Remote[A]) extends ZFlow[Any, Nothing, Remote.Variable[A]] {
    val errorSchema  = SchemaOrNothing.nothing
    val resultSchema = SchemaOrNothing.fromSchema(Remote.Variable.schema[A])
  }

  final case class Iterate[R, E, A](
    initial: Remote[A],
    step: A ===> ZFlow[R, E, A],
    predicate: A ===> Boolean
  )(implicit
    val errorSchema: SchemaOrNothing.Aux[E],
    val resultSchema: SchemaOrNothing.Aux[A]
  ) extends ZFlow[R, E, A] {
    type ValueE = E
    type ValueA = A
  }

  case object GetExecutionEnvironment extends ZFlow[Any, Nothing, ExecutionEnvironment] {
    val errorSchema  = SchemaOrNothing.nothing
    val resultSchema = SchemaOrNothing.fromSchema(Schema.fail[ExecutionEnvironment]("not serializable"))
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
      executingFlows <- ZFlow.foreach[R, Nothing, A, ExecutingFlow[ActivityError, B]](values)((remoteA: Remote[A]) =>
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
    ZFlow.now.flatMap[Any, Nothing, Unit] { now =>
      ZFlow(now.plusDuration(duration)).flatMap[Any, Nothing, Unit] { later =>
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

  val executionEnvironment: ZFlow[Any, Nothing, ExecutionEnvironment] = ZFlow.GetExecutionEnvironment

  implicit def schema[R, E, A]: Schema[ZFlow[R, E, A]] =
    Schema.EnumN(
      CaseSet
        .Cons(Return.schemaCase[R, E, A], CaseSet.Empty[ZFlow[R, E, A]]())
        .:+:(Now.schemaCase[R, E, A])
        .:+:(WaitTill.schemaCase[R, E, A])
    )
}
