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

// zio.schema
trait Schema[A]
object Schema {
  implicit def listSchema[A: Schema]: Schema[List[A]] = ???
  implicit def stringSchema: Schema[String]           = ???
  implicit def intSchema: Schema[Int]                 = ???
  implicit def boolSchema: Schema[Boolean]            = ???
}

trait Workflow[-I, +E, +A] { self =>
  final def *>[I1 <: I, E1 >: E, B](that: Workflow[I1, E1, B]): Workflow[I1, E1, B] =
    self.zip(that).map(_._2)

  final def <*[I1 <: I, E1 >: E](that: Workflow[I1, E1, Any]): Workflow[I1, E1, A] =
    self.zip(that).map(_._1)

  final def as[B](b: => B): Workflow[I, E, B] = self.map(_ => b)

  final def flatMap[I1 <: I, E1 >: E, B](f: A => Workflow[I1, E1, B]): Workflow[I1, E1, B] =
    Workflow.FlatMap(self, f)

  final def map[B](f: A => B): Workflow[I, E, B] =
    self.flatMap(a => Workflow(f(a)))

  final def unit: Workflow[I, E, Unit] = as(())

  final def zip[I1 <: I, E1 >: E, B](that: Workflow[I1, E1, B]): Workflow[I1, E1, (A, B)] =
    self.flatMap(a => that.map(b => a -> b))
}
object Workflow            {
  final case class Return[A](value: () => A)                                                extends Workflow[Any, Nothing, A]
  final case class FlatMap[I, E, A, B](value: Workflow[I, E, A], k: A => Workflow[I, E, B]) extends Workflow[I, E, B]
  final case class Modify[A, B](svar: StateVar[A], f: A => (B, A))                          extends Workflow[Any, Nothing, B]
  final case class RunActivity[E, A](activity: Activity[E, A])                              extends Workflow[Any, E, A]
  final case class Transaction[I, E, A](workflow: Workflow[I, E, A])                        extends Workflow[I, E, A]

  def apply[A](a: => A): Workflow[Any, Nothing, A] = Return(() => a)

  def define[I, S, E, A](name: String, constructor: Constructor[S])(body: S => Workflow[I, E, A]) = ???

  def input[I: Schema]: Workflow[I, Nothing, I] = ???

  def transaction[I, E, A](workflow: Workflow[I, E, A]): Workflow[I, E, A] = Transaction(workflow)
}

sealed trait Constructor[+A] { self =>
  final def flatMap[B](f: A => Constructor[B]): Constructor[B] =
    Constructor.FlatMap(self, f)

  final def map[B](f: A => B): Constructor[B] =
    self.flatMap(a => Constructor.Return(f(a)))

  final def zip[B](that: Constructor[B]): Constructor[(A, B)] =
    self.flatMap(a => that.map(b => a -> b))
}
object Constructor           {
  final case class Return[A](value: A)                                          extends Constructor[A]
  final case class NewVar[A](defaultValue: A, schema: Schema[A])                extends Constructor[StateVar[A]]
  final case class FlatMap[A, B](value: Constructor[A], k: A => Constructor[B]) extends Constructor[B]

  def apply[A](a: A): Constructor[A] = Return(a)

  def newVar[A: Schema](value: A): Constructor[StateVar[A]] = NewVar(value, implicitly[Schema[A]])
}

trait StateVar[A] { self =>
  def get: Workflow[Any, Nothing, A] = modify(a => (a, a))

  def set(a: A): Workflow[Any, Nothing, Unit] = modify(_ => ((), a))

  def modify[B](f: A => (B, A)): Workflow[Any, Nothing, B] =
    Workflow.Modify(self, f)

  def updateAndGet(f: A => A): Workflow[Any, Nothing, A] =
    modify { a =>
      val a2 = f(a); (a2, a2)
    }

  def update(f: A => A): Workflow[Any, Nothing, Unit] = updateAndGet(f).unit
}

sealed trait Activity[+E, +A] { self =>
  def run: Workflow[Any, E, A] = Workflow.RunActivity(self)
}
object Activity               {
  final case class Effect[E, A](
    effect: ZIO[Any, E, A],
    completed: ZIO[Any, E, Boolean],
    compensation: A => ZIO[Any, E, Unit]
  ) extends Activity[E, A]
}

object Example {
  import Constructor._

  type OrderId = Int

  def refundOrder(orderId: OrderId): Activity[Nothing, Unit] = ???

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
            intVar.set(orderId) *>
              boolVar.set(true) *>
              refundOrder(orderId).run *>
              listVar.set(Nil)
          }
        )
    }
}
