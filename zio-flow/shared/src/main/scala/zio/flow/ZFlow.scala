package zio.flow

import java.time.{ Duration, Instant }

import scala.reflect.ClassTag

import zio.flow.RemoteTuple._
import zio.flow.ZFlow.Die

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
  final def *>[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): ZFlow[R1, E1, B] =
    (self: ZFlow[R, E, A1]).zip(that).map(_._2)

  final def <*[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): ZFlow[R1, E1, A1] =
    (self: ZFlow[R, E, A1]).zip(that).map(_._1)

  final def as[B](b: => Remote[B]): ZFlow[R, E, B] = self.map(_ => b)

  final def catchAll[R1 <: R, E1 >: E, A1 >: A: Schema, E2](f: Remote[E] => ZFlow[R1, E2, A1]): ZFlow[R1, E2, A1] =
    (self: ZFlow[R, E, A1]).foldM(f, ZFlow(_))

  final def ensuring(flow: ZFlow[Any, Nothing, Any]): ZFlow[R, E, A] = ZFlow.Ensuring(self, flow)

  final def flatMap[R1 <: R, E1 >: E, B](f: Remote[A] => ZFlow[R1, E1, B]): ZFlow[R1, E1, B] =
    self.foldM(ZFlow.Halt(_), f)

  final def foldM[R1 <: R, E1 >: E, E2, B](
    error: Remote[E] => ZFlow[R1, E2, B],
    success: Remote[A] => ZFlow[R1, E2, B]
  ): ZFlow[R1, E2, B] = ZFlow.Fold(self, error, success)

  final def fork: ZFlow[R, Nothing, ExecutingFlow[E, A]] = ZFlow.Fork(self)

  final def ifThenElse[R1 <: R, E1 >: E, B](ifTrue: ZFlow[R1, E1, B], ifFalse: ZFlow[R1, E1, B])(implicit
    ev: A <:< Boolean
  ): ZFlow[R1, E1, B] =
    self.widen[Boolean].flatMap(bool => ZFlow.unwrap(bool.ifThenElse(Remote(ifTrue), Remote(ifFalse))))

  final def iterate[R1 <: R, E1 >: E, A1 >: A](step: Remote[A1] => ZFlow[R1, E1, A1])(
    predicate: Remote[A1] => Remote[Boolean]
  ): ZFlow[R1, E1, A1] =
    ZFlow.Iterate(self, step, predicate)

  final def map[B](f: Remote[A] => Remote[B]): ZFlow[R, E, B] =
    self.flatMap(a => ZFlow(f(a)))

  final def orDie: ZFlow[R, Nothing, A] = Die

  final def orElse[R1 <: R, E2, A1 >: A](that: ZFlow[R1, E2, A1])(implicit A1: Schema[A1]): ZFlow[R1, E2, A1] =
    (self: ZFlow[R, E, A1]).catchAll(_ => that)

  final def orElseEither[R1 <: R, E2, A1 >: A, B](
    that: ZFlow[R1, E2, B]
  )(implicit A1: Schema[A1], b: Schema[B]): ZFlow[R1, E2, Either[A1, B]] =
    (self: ZFlow[R, E, A1]).map(Left(_)).catchAll(_ => that.map(Right(_)))

  /**
   * Attempts to execute this flow, but then, if this flow is suspended due to performing a retry
   * operation inside a transaction (because conditions necessary for executing this flow are not
   * yet ready), then will switch over to the specified flow.
   *
   * If this flow never suspends, then it will always execute to success or failure, and the
   * specified flow will never be executed.
   */
  final def orTry[R1 <: R, E1 >: E, A1 >: A](that: ZFlow[R1, E1, A1]): ZFlow[R1, E1, A1] =
    ZFlow.OrTry(self, that)

  final def provide(value: Remote[R]): ZFlow[Any, E, A] = ZFlow.Provide(value, self)

  final def timeout(duration: Remote[Duration]): ZFlow[R, E, Option[A]] =
    ZFlow.Timeout(self, duration)

  final def unit: ZFlow[R, E, Unit] = as(())

  final def zip[R1 <: R, E1 >: E, A1 >: A, B](
    that: ZFlow[R1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): ZFlow[R1, E1, (A1, B)] =
    (self: ZFlow[R, E, A1]).flatMap(a => that.map(b => a -> b))

  final def widen[A0](implicit ev: A <:< A0): ZFlow[R, E, A0] = {
    val _ = ev

    self.asInstanceOf[ZFlow[R, E, A0]]
  }

  // TODO: Delete in favor of orDie???
  final def refineToOrDie[Narrowed: ClassTag](implicit v: CanFail[Narrowed]): ZFlow[R, Narrowed, A] =
    ZFlow.RefineToOrDie[R, E, Narrowed, A](self)
}

object ZFlow {

  final case class Return[A](value: Remote[A]) extends ZFlow[Any, Nothing, A]

  case object Now extends ZFlow[Any, Nothing, Instant]

  final case class WaitTill(time: Remote[Instant]) extends ZFlow[Any, Nothing, Unit]

  final case class Halt[E](value: Remote[E]) extends ZFlow[Any, E, Nothing]

  final case class Modify[A, B](svar: Variable[A], f: Remote[A] => Remote[(B, A)]) extends ZFlow[Any, Nothing, B]

  final case class Fold[R, E1, E2, A, B](
    value: ZFlow[R, E1, A],
    ke: Remote[E1] => ZFlow[R, E2, B],
    ks: Remote[A] => ZFlow[R, E2, B]
  ) extends ZFlow[R, E2, B]

  final case class RunActivity[R, A](input: Remote[R], activity: Activity[R, A]) extends ZFlow[Any, ActivityError, A]

  final case class Transaction[R, E, A](workflow: ZFlow[R, E, A]) extends ZFlow[R, E, A]

  final case class Input[R](schema: Schema[R]) extends ZFlow[R, Nothing, R]

  final case class Define[R, S, E, A](name: String, constructor: ZFlowState[S], body: S => ZFlow[R, E, A])
      extends ZFlow[R, E, A]

  final case class Ensuring[R, E, A](flow: ZFlow[R, E, A], finalizer: ZFlow[R, Nothing, Any]) extends ZFlow[R, E, A]

  final case class Unwrap[R, E, A](remote: Remote[ZFlow[R, E, A]]) extends ZFlow[R, E, A]

  final case class Foreach[R, E, A, B](values: Remote[List[A]], body: Remote[A] => ZFlow[R, E, B])
      extends ZFlow[R, E, List[B]]

  final case class Fork[R, E, A](workflow: ZFlow[R, E, A]) extends ZFlow[R, Nothing, ExecutingFlow[E, A]]

  final case class Timeout[R, E, A](value: ZFlow[R, E, A], duration: Remote[Duration]) extends ZFlow[R, E, Option[A]]

  final case class Provide[R, E, A](value: Remote[R], flow: ZFlow[R, E, A]) extends ZFlow[Any, E, A]

  case object Die extends ZFlow[Any, Nothing, Nothing]

  final case class RefineToOrDie[R, E, E1, A](value: ZFlow[R, E, A])(implicit tag: ClassTag[E1]) extends ZFlow[R, E1, A]

  case object RetryUntil extends ZFlow[Any, Nothing, Nothing]

  final case class OrTry[R, E, A](left: ZFlow[R, E, A], right: ZFlow[R, E, A]) extends ZFlow[R, E, A]

  final case class Await[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Either[E, A]]

  final case class Interrupt[E, A](exFlow: Remote[ExecutingFlow[E, A]]) extends ZFlow[Any, ActivityError, Any]

  case class Iterate[R, E, A](
    self: ZFlow[R, E, A],
    step: Remote[A] => ZFlow[R, E, A],
    predicate: Remote[A] => Remote[Boolean]
  ) extends ZFlow[R, E, A]

  final case class FoldEither[R, E, A, B, C](
    either: Remote[Either[A, B]],
    left: Remote[A] => ZFlow[R, E, C],
    right: Remote[B] => ZFlow[R, E, C]
  ) extends ZFlow[R, E, C]

  def apply[A: Schema](a: A): ZFlow[Any, Nothing, A] = Return(Remote(a))

  def apply[A](remote: Remote[A]): ZFlow[Any, Nothing, A] = Return(remote)

  def define[R, S, E, A](name: String, constructor: ZFlowState[S])(body: S => ZFlow[R, E, A]): ZFlow[R, E, A] =
    Define(name, constructor, body)

  def doUntil[R, E](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Any] =
    ZFlow(false).iterate((_: Remote[Boolean]) => flow)(_ == false)

  def doWhile[R, E](flow: ZFlow[R, E, Boolean]): ZFlow[R, E, Any] =
    ZFlow(true).iterate((_: Remote[Boolean]) => flow)(_ == true)

  def foreach[R, E, A, B](values: Remote[List[A]])(body: Remote[A] => ZFlow[R, E, B]): ZFlow[R, E, List[B]] =
    Foreach(values, body)

  // TODO: Try to implement this one in terms of `foreach`, `fork`, and `await`.
  //TODO : Add a method FoldEitherM on RemoteEither
  def foreachPar[R, A, B](
    values: Remote[List[A]]
  )(body: Remote[A] => ZFlow[R, ActivityError, B]): ZFlow[R, ActivityError, List[B]] = ???
//    (foreach(values)((remoteA: Remote[A]) => body(remoteA).fork)).flatMap(exFlowList =>
//      foreach(exFlowList)(_.await)).map(eitherList => RemoteEither.collectAll(eitherList)).flatMap(either)

  def ifThenElse[R, E, A](p: Remote[Boolean])(ifTrue: ZFlow[R, E, A], ifFalse: ZFlow[R, E, A]): ZFlow[R, E, A] =
    ZFlow.unwrap(p.ifThenElse(ifTrue, ifFalse))

  def input[R: Schema]: ZFlow[R, Nothing, R] = Input(implicitly[Schema[R]])

  def now: ZFlow[Any, Nothing, Instant] = Now

  def sleep(duration: Remote[Duration]): ZFlow[Any, Nothing, Unit] =
    for {
      now   <- ZFlow.now
      later <- ZFlow(now.plusDuration(duration))
      _     <- ZFlow.waitTill(later)
    } yield Remote.unit

  def transaction[R, E, A](make: ZFlowTransaction => ZFlow[R, E, A]): ZFlow[R, E, A] =
    Transaction(make(ZFlowTransaction.instance))

  val unit: ZFlow[Any, Nothing, Unit] = ZFlow(Remote.unit)

  def unwrap[R, E, A](remote: Remote[ZFlow[R, E, A]]): ZFlow[R, E, A] =
    Unwrap(remote)

  def waitTill(instant: Remote[Instant]): ZFlow[Any, Nothing, Unit] = WaitTill(instant)

  implicit def schemaZFlow[R, E, A]: Schema[ZFlow[R, E, A]] = ???

  def when[R, E](predicate: Remote[Boolean])(flow: ZFlow[R, E, Any]): ZFlow[R, E, Any] =
    ZFlow.ifThenElse(predicate)(flow, ZFlow.unit)

}
