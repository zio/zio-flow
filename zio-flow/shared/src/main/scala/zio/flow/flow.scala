package zio.flow

import scala.language.implicitConversions

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

// zio.schema
trait Schema[A]
object Schema {
  implicit def nilSchema: Schema[Nil.type]                              = ???
  implicit def listSchema[A: Schema]: Schema[List[A]]                   = ???
  implicit def stringSchema: Schema[String]                             = ???
  implicit def intSchema: Schema[Int]                                   = ???
  implicit def unitSchema: Schema[Unit]                                 = ???
  implicit def boolSchema: Schema[Boolean]                              = ???
  implicit def leftSchema[A: Schema]: Schema[Left[A, Nothing]]          = ???
  implicit def rightSchema[B: Schema]: Schema[Right[Nothing, B]]        = ???
  implicit def schemaTuple2[A: Schema, B: Schema]: Schema[(A, B)]       = ???
  implicit def schemaEither[A: Schema, B: Schema]: Schema[Either[A, B]] = ???
}

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
    ke: Cause[E1] => Workflow[I, E2, B],
    ks: A => Workflow[I, E2, B]
  )                                                                                  extends Workflow[I, E2, B]
  final case class RunActivity[I, E, A](input: Expr[I], activity: Activity[I, E, A]) extends Workflow[Any, E, A]
  final case class Transaction[I, E, A](workflow: Workflow[I, E, A])                 extends Workflow[I, E, A]

  def apply[A: Schema](a: A): Workflow[Any, Nothing, A] = Return(a, implicitly[Schema[A]])

  def define[I, S, E, A](name: String, constructor: Constructor[S])(body: S => Workflow[I, E, A]) = ???

  def input[I: Schema]: Workflow[I, Nothing, Expr[I]] = ???

  def transaction[I, E, A](workflow: Workflow[I, E, A]): Workflow[I, E, A] = Transaction(workflow)
}

sealed trait Constructor[+A] { self =>
  final def flatMap[B](f: Expr[A] => Constructor[B]): Constructor[B] =
    Constructor.FlatMap(self, f)

  final def map[B](f: Expr[A] => Expr[B]): Constructor[B] =
    self.flatMap(a => Constructor.Return(f(a)))

  final def zip[B](that: Constructor[B]): Constructor[(A, B)] =
    self.flatMap(a => that.map(b => a -> b))
}
object Constructor           {
  final case class Return[A](value: Expr[A])                                          extends Constructor[A]
  final case class NewVar[A](defaultValue: Expr[A])                                   extends Constructor[StateVar[A]]
  final case class FlatMap[A, B](value: Constructor[A], k: Expr[A] => Constructor[B]) extends Constructor[B]

  def apply[A: Schema](a: A): Constructor[A] = Return(a)

  def newVar[A](value: Expr[A]): Constructor[StateVar[A]] = NewVar(value)
}

trait StateVar[A] { self =>
  def get: Workflow[Any, Nothing, Expr[A]] = modify(a => (a, a))

  def set(a: Expr[A]): Workflow[Any, Nothing, Expr[Unit]] =
    modify[Unit](_ => ((), a))

  def modify[B](f: Expr[A] => (Expr[B], Expr[A])): Workflow[Any, Nothing, Expr[B]] =
    Workflow.Modify(self, (e: Expr[A]) => Expr.tuple2(f(e)))

  def updateAndGet(f: Expr[A] => Expr[A]): Workflow[Any, Nothing, Expr[A]] =
    modify { a =>
      val a2 = f(a)
      (a2, a2)
    }

  def update(f: Expr[A] => Expr[A]): Workflow[Any, Nothing, Unit] = updateAndGet(f).unit
}

sealed trait Activity[-I, +E, +A] { self =>
  def run(input: Expr[I]): Workflow[Any, E, A] = Workflow.RunActivity(input, self)
}
object Activity                   {
  final case class Effect[I, E, A](
    uniqueIdentifier: String,
    effect: I => ZIO[Any, E, A],
    completed: ZIO[Any, E, Boolean],
    compensation: A => ZIO[Any, E, Unit],
    description: String
  ) extends Activity[I, E, A]
}

sealed trait Expr[+A] { self =>
  final def ->[B](that: Expr[B]): Expr[(A, B)] = Expr.tuple2((self, that))

  final def +(that: Expr[Int])(implicit ev: A <:< Int): Expr[Int] =
    Expr.AddInt(self.widen[Int], that)

  final def widen[B](implicit ev: A <:< B): Expr[B] = {
    val _ = ev

    self.asInstanceOf[Expr[B]]
  }
}
object Expr           {
  final case class Literal[A](value: A, schema: Schema[A])                extends Expr[A]
  final case class Variable[A](identifier: String)                        extends Expr[A]
  final case class AddInt(left: Expr[Int], right: Expr[Int])              extends Expr[Int]
  final case class Tuple2[A, B](left: Expr[A], right: Expr[B])            extends Expr[(A, B)]
  final case class Tuple3[A, B, C](_1: Expr[A], _2: Expr[B], _3: Expr[C]) extends Expr[(A, B, C)]

  implicit def apply[A: Schema](value: A): Expr[A] =
    Literal(value, implicitly[Schema[A]])

  implicit def tuple2[A, B](t: (Expr[A], Expr[B])): Expr[(A, B)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[A, B, C](t: (Expr[A], Expr[B], Expr[C])): Expr[(A, B, C)] =
    Tuple3(t._1, t._2, t._3)

  val unit: Expr[Unit] = Expr(())

  implicit def schemaExpr[A]: Schema[A] = ???
}

object Example {
  // Expr[A] => Expr[(B, A)]

  import Constructor._

  type OrderId = Int

  lazy val refundOrder: Activity[OrderId, Nothing, Unit] =
    Activity.Effect("refund-order", ???, ???, ???, "Refunds an order with the specified orderId")

  val stateConstructor: Constructor[(StateVar[Int], StateVar[Boolean], StateVar[List[String]])] =
    for {
      intVar  <- newVar[Int](0)
      boolVar <- newVar[Boolean](false)
      listVar <- newVar[List[String]](Nil)
    } yield (intVar, boolVar, listVar)

  val orderProcess: Nothing =
    Workflow.define("order-process", stateConstructor) { case (intVar, boolVar, listVar) =>
      Workflow
        .input[OrderId]
        .flatMap(orderId =>
          Workflow.transaction {
            intVar.update(_ + orderId) *>
              boolVar.set(true) *>
              refundOrder.run(orderId) *>
              listVar.set(Nil)
          }
        )
    }
}
