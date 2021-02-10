package zio.flow
import scala.language.implicitConversions

// TODO: Replace by ZIO Schema
trait Schema[A]
object Schema {
  def apply[A](implicit schema: Schema[A]): Schema[A] = schema

  implicit def nilSchema: Schema[Nil.type]                                      = ???
  implicit def listSchema[A: Schema]: Schema[List[A]]                           = ???
  implicit def stringSchema: Schema[String]                                     = ???
  implicit def shortSchema: Schema[Short]                                       = ???
  implicit def intSchema: Schema[Int]                                           = ???
  implicit def longSchema: Schema[Long]                                         = ???
  implicit def floatSchema: Schema[Float]                                       = ???
  implicit def doubleSchema: Schema[Double]                                     = ???
  implicit def bigIntSchema: Schema[BigInt]                                     = ???
  implicit def bigDecimalSchema: Schema[BigDecimal]                             = ???
  implicit def unitSchema: Schema[Unit]                                         = ???
  implicit def boolSchema: Schema[Boolean]                                      = ???
  implicit def leftSchema[A: Schema]: Schema[Left[A, Nothing]]                  = ???
  implicit def rightSchema[B: Schema]: Schema[Right[Nothing, B]]                = ???
  implicit def schemaTuple2[A: Schema, B: Schema]: Schema[(A, B)]               = ???
  implicit def schemaTuple3[A: Schema, B: Schema, C: Schema]: Schema[(A, B, C)] = ???
  implicit def schemaEither[A: Schema, B: Schema]: Schema[Either[A, B]]         = ???
  implicit def schemaNothing: Schema[Nothing]                                   = ???
}

sealed trait Expr[+A]
    extends ExprSortable[A]
    with ExprBoolean[A]
    with ExprTuple[A]
    with ExprList[A]
    with ExprIntegral[A] {
  def self: Expr[A] = this

  def schema: Schema[_ <: A]

  final def iterate[A1 >: A](step: Expr[A1] => Expr[A1])(predicate: Expr[A1] => Expr[Boolean]): Expr[A1] =
    predicate(self).ifThenElse(
      step(self).iterate(step)(predicate),
      self
    )

  final def toFlow: ZFlow[Any, Nothing, A] = ZFlow(self)

  final def widen[B](implicit ev: A <:< B): Expr[B] = {
    val _ = ev

    self.asInstanceOf[Expr[B]]
  }

  final def unit: Expr[Unit] = Expr.Ignore(self)
}
object Expr              {
  final case class Literal[A](value: A, schema: Schema[A])                                          extends Expr[A]
  final case class Ignore[A](value: Expr[A])                                                        extends Expr[Unit]         {
    def schema: Schema[Unit] = Schema[Unit]
  }
  final case class Variable[A](identifier: String, schema: Schema[A])                               extends Expr[A]
  final case class AddIntegral[A](left: Expr[A], right: Expr[A], numeric: Integral[A])              extends Expr[A]            {
    def schema = numeric.schema
  }
  final case class DivIntegral[A](left: Expr[A], right: Expr[A], numeric: Integral[A])              extends Expr[A]            {
    def schema = numeric.schema
  }
  final case class MulIntegral[A](left: Expr[A], right: Expr[A], numeric: Integral[A])              extends Expr[A]            {
    def schema = numeric.schema
  }
  final case class PowIntegral[A](left: Expr[A], right: Expr[A], numeric: Integral[A])              extends Expr[A]            {
    def schema = numeric.schema
  }
  final case class Negation[A](value: Expr[A], numeric: Integral[A])                                 extends Expr[A]            {
    def schema = numeric.schema
  }
  final case class Either0[A, B](either: Either[Expr[A], Expr[B]])                                  extends Expr[Either[A, B]] {
    def schema = ???
  }
  final case class FoldEither[A, B, C](either: Expr[Either[A, B]], left: Expr[A] => Expr[C], right: Expr[B] => Expr[C])
      extends Expr[C] {
    def schema = ???
  }
  final case class Tuple2[A, B](left: Expr[A], right: Expr[B])                                      extends Expr[(A, B)]       {
    def schema = ???
  }
  final case class Tuple3[A, B, C](_1: Expr[A], _2: Expr[B], _3: Expr[C])                           extends Expr[(A, B, C)]    {
    def schema = ???
  }
  final case class First[A, B](tuple: Expr[(A, B)])                                                 extends Expr[A]            {
    def schema = ???
  }
  final case class Second[A, B](tuple: Expr[(A, B)])                                                extends Expr[B]            {
    def schema = ???
  }
  final case class Branch[A](predicate: Expr[Boolean], ifTrue: Expr[A], ifFalse: Expr[A])           extends Expr[A]            {
    def schema = ifTrue.schema
  }
  final case class LessThanEqual[A](left: Expr[A], right: Expr[A], sortable: Sortable[A])           extends Expr[Boolean]      {
    def schema: Schema[Boolean] = Schema[Boolean]
  }
  final case class Not[A](value: Expr[Boolean])                                                     extends Expr[Boolean]      {
    def schema: Schema[Boolean] = Schema[Boolean]
  }
  final case class And[A](left: Expr[Boolean], right: Expr[Boolean])                                extends Expr[Boolean]      {
    def schema: Schema[Boolean] = Schema[Boolean]
  }
  final case class Fold[A, B](list: Expr[List[A]], initial: Expr[B], body: Expr[(B, A)] => Expr[B]) extends Expr[B]            {
    def schema = initial.schema
  }
  final case class Iterate[A](initial: Expr[A], iterate: Expr[A] => Expr[A], predicate: Expr[A] => Expr[Boolean])
      extends Expr[A] {
    def schema = initial.schema
  }

  implicit def apply[A: Schema](value: A): Expr[A] =
    Literal(value, implicitly[Schema[A]])

  implicit def tuple2[A, B](t: (Expr[A], Expr[B])): Expr[(A, B)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[A, B, C](t: (Expr[A], Expr[B], Expr[C])): Expr[(A, B, C)] =
    Tuple3(t._1, t._2, t._3)

  implicit def either[A, B](either0: Either[Expr[A], Expr[B]]): Expr[Either[A, B]] =
    Either0(either0)

  implicit def toFlow[A](expr: Expr[A]): ZFlow[Any, Nothing, A] = expr.toFlow

  val unit: Expr[Unit] = Expr(())

  implicit def schemaExpr[A]: Schema[Expr[A]] = ???
}
