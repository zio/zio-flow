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
import zio.schema.Schema

import java.time.{Duration, Instant}
import scala.language.implicitConversions

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
      RemoteFunction(error.andThen(Remote[ZFlow[R1, E2, B]]))(errorSchema.asInstanceOf[Schema[E]]),
      RemoteFunction(success.andThen(Remote[ZFlow[R1, E2, B]]))(resultSchema.asInstanceOf[Schema[A]])
    )

  final def fork[E1 >: E: Schema, A1 >: A: Schema]: ZFlow[R, Nothing, ExecutingFlow[E1, A1]] =
    ZFlow.Fork[R, E1, A1](self)

  final def ifThenElse[R1 <: R, E1 >: E: Schema, B: Schema](ifTrue: ZFlow[R1, E1, B], ifFalse: ZFlow[R1, E1, B])(
    implicit ev: A <:< Boolean
  ): ZFlow[R1, E1, B] =
    self
      .widen[Boolean]
      .flatMap((bool: Remote[Boolean]) => ZFlow.unwrap(bool.ifThenElse(Remote(ifTrue), Remote(ifFalse))))

  final def iterate[R1 <: R: Schema, E1 >: E: Schema, A1 >: A: Schema](step: Remote[A1] => ZFlow[R1, E1, A1])(
    predicate: Remote[A1] => Remote[Boolean]
  ): ZFlow[R1, E1, A1] =
    self.flatMap((remoteA: Remote[A1]) =>
      ZFlow.Iterate(
        remoteA,
        RemoteFunction((a1: Remote[A1]) => Remote(step(a1))),
        RemoteFunction(predicate)
      )
    )

  final def map[B: Schema](f: Remote[A] => Remote[B]): ZFlow[R, E, B] =
    self.flatMap[R, E, B](a => ZFlow(f(a)))(errorSchema.asInstanceOf[Schema[E]], Schema[B])

  final def orDie[R1 <: R, E1 >: E: Schema, A1 >: A: Schema]: ZFlow[R1, Nothing, A1] =
    self.catchAll[R1, E1, A1, Nothing]((_: Remote[E1]) => ZFlow.Die)

  final def orElse[R1 <: R, E1 >: E: Schema, E2: Schema, A1 >: A: Schema](that: ZFlow[R1, E2, A1]): ZFlow[R1, E2, A1] =
    self.catchAll((_: Remote[E1]) => that)

  final def orElseEither[R1 <: R, A1 >: A: Schema, E2: Schema, B: Schema](
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

  final def timeout[E1 >: E: Schema, A1 >: A: Schema](duration: Remote[Duration]): ZFlow[R, E1, Option[A1]] =
    ZFlow.Timeout[R, E1, A1](self, duration)

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
    val errorSchema  = nothingSchema
    val resultSchema = value.schema
  }

  case object Now extends ZFlow[Any, Nothing, Instant] {
    val errorSchema                   = nothingSchema
    val resultSchema: Schema[Instant] = Schema[Instant]
  }

  final case class WaitTill(time: Remote[Instant]) extends ZFlow[Any, Nothing, Unit] {
    val errorSchema                = nothingSchema
    val resultSchema: Schema[Unit] = Schema[Unit]
  }

  final case class Modify[A, B: Schema](svar: Remote.Variable[A], f: A ===> (B, A)) extends ZFlow[Any, Nothing, B] {
    val errorSchema             = nothingSchema
    val resultSchema: Schema[B] = Schema[B]
  }

  final case class Fold[R, E, E2: Schema, A, B: Schema](
    value: ZFlow[R, E, A],
    ifError: RemoteFunction[E, ZFlow[R, E2, B]],
    ifSuccess: RemoteFunction[A, ZFlow[R, E2, B]]
  ) extends ZFlow[R, E2, B] {
    type ValueE  = E
    type ValueE2 = E2
    type ValueA  = A
    type ValueR  = R
    type ValueB  = B

    val errorSchema  = Schema[E2]
    val resultSchema = Schema[B]
  }

  final case class Apply[A, E: Schema, B: Schema](lambda: A ===> ZFlow[Any, E, B]) extends ZFlow[A, E, B] {
    type ValueE = E
    type ValueA = A
    type ValueB = B

    val errorSchema             = Schema[E]
    val resultSchema: Schema[B] = Schema[B]
  }

  final case class Log(message: String) extends ZFlow[Any, Nothing, Unit] {
    val errorSchema                = nothingSchema
    val resultSchema: Schema[Unit] = Schema[Unit]
  }

  final case class RunActivity[R, A: Schema](input: Remote[R], activity: Activity[R, A])
      extends ZFlow[Any, ActivityError, A] {
    val errorSchema             = Schema[ActivityError]
    val resultSchema: Schema[A] = Schema[A]
  }

  final case class Transaction[R, E: Schema, A: Schema](workflow: ZFlow[R, E, A]) extends ZFlow[R, E, A] {
    type ValueR = R
    val errorSchema             = Schema[E]
    val resultSchema: Schema[A] = Schema[A]
  }

  final case class Input[R: Schema]() extends ZFlow[R, Nothing, R] {
    val errorSchema             = nothingSchema
    val resultSchema: Schema[R] = Schema[R]
  }

  final case class Ensuring[R, E, A](flow: ZFlow[R, E, A], finalizer: ZFlow[Any, Nothing, Unit])
      extends ZFlow[R, E, A] {
    type ValueR = R
    type ValueE = E
    type ValueA = A

    val errorSchema  = flow.errorSchema
    val resultSchema = flow.resultSchema
  }

  final case class Unwrap[R, E: Schema, A: Schema](remote: Remote[ZFlow[R, E, A]]) extends ZFlow[R, E, A] {
    val errorSchema: Schema[E]  = Schema[E]
    val resultSchema: Schema[A] = Schema[A]
  }

  final case class UnwrapRemote[A: Schema](remote: Remote[Remote[A]]) extends ZFlow[Any, Nothing, A] {
    val errorSchema  = nothingSchema
    val resultSchema = Schema[A]
  }

  final case class Fork[R, E: Schema, A: Schema](workflow: ZFlow[R, E, A])
      extends ZFlow[R, Nothing, ExecutingFlow[E, A]] {
    type ValueE = E
    type ValueA = A
    val schemaE: Schema[E]                        = Schema[E]
    val schemaA: Schema[A]                        = Schema[A]
    val errorSchema                               = nothingSchema
    val resultSchema: Schema[ExecutingFlow[E, A]] = Schema[ExecutingFlow[E, A]]
  }

  final case class Timeout[R, E: Schema, A: Schema](flow: ZFlow[R, E, A], duration: Remote[Duration])
      extends ZFlow[R, E, Option[A]] {
    type ValueA = A
    type ValueE = E
    val schemaEitherE: Schema[Either[Throwable, E]] = Schema[Either[Throwable, E]]
    val schemaE: Schema[E]                          = Schema[E]
    val schemaA: Schema[A]                          = Schema[A]
    val errorSchema                                 = Schema[E]
    val resultSchema: Schema[Option[A]]             = Schema[Option[A]]
  }

  final case class Provide[R, E, A](value: Remote[R], flow: ZFlow[R, E, A]) extends ZFlow[Any, E, A] {
    type ValueE = E
    type ValueA = A
    val errorSchema  = flow.errorSchema
    val resultSchema = flow.resultSchema
  }

  case object Die extends ZFlow[Any, Nothing, Nothing] {
    val errorSchema                   = nothingSchema
    val resultSchema: Schema[Nothing] = nothingSchema
  }

  case object RetryUntil extends ZFlow[Any, Nothing, Nothing] {
    val errorSchema                   = nothingSchema
    val resultSchema: Schema[Nothing] = nothingSchema
  }

  final case class OrTry[R, E, A](left: ZFlow[R, E, A], right: ZFlow[R, E, A]) extends ZFlow[R, E, A] {
    val errorSchema  = left.errorSchema
    val resultSchema = left.resultSchema
  }

  final case class Await[E: Schema, A: Schema](exFlow: Remote[ExecutingFlow[E, A]])
      extends ZFlow[Any, ActivityError, Either[E, A]] {
    type ValueE = E
    type ValueA = A
    val schemaE: Schema[E]                          = Schema[E]
    val schemaEitherE: Schema[Either[Throwable, E]] = Schema[Either[Throwable, E]]
    val schemaA: Schema[A]                          = Schema[A]
    val errorSchema                                 = Schema[ActivityError]
    val resultSchema: Schema[Either[E, A]]          = Schema[Either[E, A]]
  }

  final case class Interrupt[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Unit] {
    val errorSchema                = Schema[ActivityError]
    val resultSchema: Schema[Unit] = Schema[Unit]
  }

  final case class Fail[E](error: Remote[E]) extends ZFlow[Any, E, Nothing] {
    val errorSchema  = error.schema
    val resultSchema = nothingSchema
  }

  final case class NewVar[A: Schema](name: String, initial: Remote[A]) extends ZFlow[Any, Nothing, Remote.Variable[A]] {
    val errorSchema                              = nothingSchema
    val resultSchema: Schema[Remote.Variable[A]] = Schema[Remote.Variable[A]]
  }

  final case class Iterate[R, E: Schema, A: Schema](
    initial: Remote[A],
    step: A ===> ZFlow[R, E, A],
    predicate: A ===> Boolean
  ) extends ZFlow[R, E, A] {
    type ValueE = E
    type ValueA = A

    val errorSchema: Schema[E]  = Schema[E]
    val resultSchema: Schema[A] = Schema[A]
  }

  case object GetExecutionEnvironment extends ZFlow[Any, Nothing, ExecutionEnvironment] {
    val errorSchema                                = nothingSchema
    val resultSchema: Schema[ExecutionEnvironment] = Schema.fail("not serializable")
  }

  def apply[A: Schema](a: A): ZFlow[Any, Nothing, A] = Return(Remote(a))

  def apply[A](remote: Remote[A]): ZFlow[Any, Nothing, A] = Return(remote)

  def doUntil[R: Schema, E: Schema](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
    ZFlow(false).iterate((_: Remote[Boolean]) => flow)(_ === false)

  def doWhile[R: Schema, E: Schema](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Boolean] =
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
      executingFlows <- ZFlow.foreach[R, Nothing, A, ExecutingFlow[ActivityError, B]](values)((remoteA: Remote[A]) =>
                          body(remoteA).fork
                        )
      eithers <- ZFlow.foreach(executingFlows)(remote => ZFlow.Await(remote))
      bs      <- ZFlow.fromEither(remote.RemoteEitherSyntax.collectAll(eithers))
    } yield bs

  def ifThenElse[R, E: Schema, A: Schema](
    p: Remote[Boolean]
  )(ifTrue: ZFlow[R, E, A], ifFalse: ZFlow[R, E, A]): ZFlow[R, E, A] =
    ZFlow.unwrap(p.ifThenElse(ifTrue, ifFalse))

  def input[R: Schema]: ZFlow[R, Nothing, R] = Input[R]()

  def newVar[A: Schema](name: String, initial: Remote[A]): ZFlow[Any, Nothing, Remote.Variable[A]] =
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

  def transaction[R, E: Schema, A: Schema](make: ZFlowTransaction => ZFlow[R, E, A]): ZFlow[R, E, A] =
    Transaction(make(ZFlowTransaction.instance))

  val unit: ZFlow[Any, Nothing, Unit] = ZFlow(Remote.unit)

  def unwrap[R, E: Schema, A: Schema](remote: Remote[ZFlow[R, E, A]]): ZFlow[R, E, A] =
    Unwrap(remote)

  def unwrapRemote[A: Schema](remote: Remote[Remote[A]]): ZFlow[Any, Nothing, A] =
    UnwrapRemote(remote)

  def waitTill(instant: Remote[Instant]): ZFlow[Any, Nothing, Unit] = WaitTill(instant)

  def when[R: Schema, E: Schema, A: Schema](predicate: Remote[Boolean])(flow: ZFlow[R, E, A]): ZFlow[R, E, Unit] =
    ZFlow.ifThenElse[R, E, Unit](predicate)(flow.unit, ZFlow.unit)

  def fail[E](error: Remote[E]): ZFlow[Any, E, Nothing] = ZFlow.Fail(error)

  def succeed[A](value: Remote[A]): ZFlow[Any, Nothing, A] = ZFlow.Return(value)

  def fromEither[E: Schema, A: Schema](either: Remote[Either[E, A]]): ZFlow[Any, E, A] =
    ZFlow.unwrap(either.handleEither((e: Remote[E]) => ZFlow.fail(e), (a: Remote[A]) => ZFlow.succeed(a)))

  def log(message: String): Log = ZFlow.Log(message)

  val executionEnvironment: ZFlow[Any, Nothing, ExecutionEnvironment] = ZFlow.GetExecutionEnvironment

//  object Schemas {
//    implicit val returnSchema: Schema[Return[_]] = Schema.CaseClass1()
//    implicit val nowSchema: Schema[Now.type] = DeriveSchema.gen
//  }
//  val erasedSchema: Schema[ZFlow[_, _, _]] = {
//    import Schemas._
//    Schema.enumeration(
//      caseOf[Return[_], ZFlow[_, _, _]](id = "Return")((flow: ZFlow[_, _, _]) => flow.asInstanceOf[Return[_]]) ++
//      caseOf[Now.type, ZFlow[_, _, _]](id = "Now")((flow: ZFlow[_, _, _]) => flow.asInstanceOf[Now.type])
//    )
//  }
//
//  implicit def schema[R, E, A]: Schema[ZFlow[R, E, A]] =
//    erasedSchema.transform(
//      _.asInstanceOf[ZFlow[R, E, A]],
//      _.asInstanceOf[ZFlow[_, _, _]]
//    )

  implicit def schema[R, E, A]: Schema[ZFlow[R, E, A]] = Schema.fail("TODO")
}
