package zio.flow

import zio._

//
// Workflow - models a workflow
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
sealed trait Workflow[-I, +E, +A] { self =>
  final def *>[I1 <: I, E1 >: E, A1 >: A, B](
    that: Workflow[I1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): Workflow[I1, E1, B] =
    (self: Workflow[I, E, A1]).zip(that).map(_._2)

  final def <*[I1 <: I, E1 >: E, A1 >: A, B](
    that: Workflow[I1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): Workflow[I1, E1, A1] =
    (self: Workflow[I, E, A1]).zip(that).map(_._1)

  final def as[B](b: => B)(implicit s: Schema[B]): Workflow[I, E, B] = self.map(_ => b)

  final def catchAll[I1 <: I, E1 >: E, A1 >: A, E2](f: E => Workflow[I1, E2, A1])(implicit
    A1: Schema[A1]
  ): Workflow[I1, E2, A1] =
    (self: Workflow[I, E, A1]).foldM(f, Workflow(_))

  final def flatMap[I1 <: I, E1 >: E, B](f: A => Workflow[I1, E1, B]): Workflow[I1, E1, B] =
    Workflow.Fold(self, (cause: Cause[E1]) => Workflow.Halt(cause), f)

  final def foldCauseM[I1 <: I, E1 >: E, E2, B](
    error: Cause[E] => Workflow[I1, E2, B],
    success: A => Workflow[I1, E2, B]
  ): Workflow[I1, E2, B] =
    Workflow.Fold(self, error, success)

  final def foldM[I1 <: I, E1 >: E, E2, B](
    error: E => Workflow[I1, E2, B],
    success: A => Workflow[I1, E2, B]
  ): Workflow[I1, E2, B] =
    self.foldCauseM(c => c.failureOrCause.fold(error, Workflow.Halt(_)), success)

  final def map[B: Schema](f: A => B): Workflow[I, E, B] =
    self.flatMap(a => Workflow(f(a)))

  final def orElse[I1 <: I, E2, A1 >: A](that: Workflow[I1, E2, A1])(implicit A1: Schema[A1]): Workflow[I1, E2, A1] =
    (self: Workflow[I, E, A1]).catchAll(_ => that)

  final def orElseEither[I1 <: I, E2, A1 >: A, B](
    that: Workflow[I1, E2, B]
  )(implicit A1: Schema[A1], b: Schema[B]): Workflow[I1, E2, Either[A1, B]] =
    (self: Workflow[I, E, A1]).map(Left(_)).catchAll(_ => that.map(Right(_)))

  final def unit: Workflow[I, E, Unit] = as(())

  final def zip[I1 <: I, E1 >: E, A1 >: A, B](
    that: Workflow[I1, E1, B]
  )(implicit A1: Schema[A1], B: Schema[B]): Workflow[I1, E1, (A1, B)] =
    (self: Workflow[I, E, A1]).flatMap(a => that.map(b => a -> b))
}
object Workflow                   {
  final case class Return[A](value: A, schema: Schema[A])                            extends Workflow[Any, Nothing, A]
  final case class Halt[E](value: Cause[E])                                          extends Workflow[Any, E, Nothing]
  final case class Modify[A, B](svar: StateVar[A], f: Expr[A] => Expr[(B, A)])       extends Workflow[Any, Nothing, Expr[B]]
  final case class Fold[I, E1, E2, A, B](
    value: Workflow[I, E1, A],
    ke: zio.Cause[E1] => Workflow[I, E2, B],
    ks: A => Workflow[I, E2, B]
  )                                                                                  extends Workflow[I, E2, B]
  final case class RunActivity[I, E, A](input: Expr[I], activity: Activity[I, E, A]) extends Workflow[Any, E, A]
  final case class Transaction[I, E, A](workflow: Workflow[I, E, A])                 extends Workflow[I, E, A]

  def apply[A: Schema](a: A): Workflow[Any, Nothing, A] = Return(a, implicitly[Schema[A]])

  def define[I, S, E, A](name: String, constructor: Constructor[S])(body: S => Workflow[I, E, A]) = ???

  def input[I: Schema]: Workflow[I, Nothing, Expr[I]] = ???

  def transaction[I, E, A](workflow: Workflow[I, E, A]): Workflow[I, E, A] = Transaction(workflow)
}
