package zio.flow

import java.time.temporal.{ ChronoUnit, TemporalUnit }
import java.time.{ Duration, Instant }
import scala.language.implicitConversions

// TODO: Replace by ZIO Schema
trait Schema[A]

object Schema {
  def apply[A](implicit schema: Schema[A]): Schema[A] = schema

  // FIXME: Add this to ZIO Schema
  def fail[A](message: String): Schema[A] = ???

  final case class SchemaTuple2[A: Schema, B: Schema]() extends Schema[(A, B)] {
    def leftSchema: Schema[A]  = Schema[A]
    def rightSchema: Schema[B] = Schema[B]
  }

  implicit def nilSchema: Schema[Nil.type] = ???

  implicit def listSchema[A: Schema]: Schema[List[A]] = ???

  implicit def stringSchema: Schema[String] = ???

  implicit def shortSchema: Schema[Short] = ???

  implicit def intSchema: Schema[Int] = ???

  implicit def longSchema: Schema[Long] = ???

  implicit def floatSchema: Schema[Float] = ???

  implicit def doubleSchema: Schema[Double] = ???

  implicit def bigIntSchema: Schema[BigInt] = ???

  implicit def bigDecimalSchema: Schema[BigDecimal] = ???

  implicit def unitSchema: Schema[Unit] = ???

  implicit def boolSchema: Schema[Boolean] = ???

  implicit def leftSchema[A: Schema]: Schema[Left[A, Nothing]] = ???

  implicit def rightSchema[B: Schema]: Schema[Right[Nothing, B]] = ???

  implicit def schemaTuple2[A: Schema, B: Schema]: Schema[(A, B)] = ???

  implicit def schemaTuple3[A: Schema, B: Schema, C: Schema]: Schema[(A, B, C)] = ???

  implicit def schemaEither[A: Schema, B: Schema]: Schema[Either[A, B]] = ???

  implicit def schemaNothing: Schema[Nothing] = ???

  implicit def chronoUnitSchema: Schema[ChronoUnit] = ???

  implicit def temporalUnitSchema: Schema[TemporalUnit] = ???
}

sealed trait Expr[+A]
    extends ExprSortable[A]
    with ExprBoolean[A]
    with ExprTuple[A]
    with ExprList[A]
    with ExprNumeric[A]
    with ExprFractional[A]
    with ExprInstant[A]
    with ExprDuration[A] {
  def eval: Either[Expr[A], A] = ???

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

  override def equals(that: Any): Boolean =
    that match {
      case that: Expr[a] => Expr.checkEquality(self, that)
      case _             => false
    }
}

object Expr {

  final case class Literal[A](value: A, schema: Schema[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = Right(value)
  }

  final case class Ignore[A](value: Expr[A]) extends Expr[Unit] {
    override def eval: Either[Expr[Unit], Unit] = Right(())

    def schema: Schema[Unit] = Schema[Unit]
  }

  final case class Variable[A](identifier: String, schema: Schema[A])                extends Expr[A] {
    override def eval: Either[Expr[A], A] = Left(self)
  }
  final case class AddNumeric[A](left: Expr[A], right: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      val leftEither  = left.eval
      val rightEither = right.eval

      (for {
        l <- leftEither
        r <- rightEither
      } yield numeric.plus(l, r)) match {
        case Left(_)  =>
          val reducedLeft  = leftEither.fold(identity, Expr(_)(numeric.schema))
          val reducedRight = rightEither.fold(identity, Expr(_)(numeric.schema))

          Left(AddNumeric(reducedLeft, reducedRight, numeric))
        case Right(v) => Right(v)
      }
    }

    def schema = numeric.schema
  }

  final case class DivNumeric[A](left: Expr[A], right: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    def schema = numeric.schema
  }

  final case class MulNumeric[A](left: Expr[A], right: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    def schema = numeric.schema
  }

  final case class PowNumeric[A](left: Expr[A], right: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    def schema = numeric.schema
  }

  final case class NegationNumeric[A](value: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    def schema = numeric.schema
  }

  final case class RootNumeric[A](value: Expr[A], n: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    def schema = numeric.schema
  }

  final case class LogNumeric[A](value: Expr[A], base: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    def schema = numeric.schema
  }

  final case class LogFractional[A](value: Expr[A], base: Expr[A], numeric: Fractional[A]) extends Expr[A] {
    def schema = numeric.schema
  }
  final case class SinFractional[A](value: Expr[A], fractional: Fractional[A])             extends Expr[A] {
    def schema = fractional.schema
  }

  final case class SinInverseFractional[A](value: Expr[A], fractional: Fractional[A]) extends Expr[A] {
    def schema = fractional.schema
  }

  final case class Either0[A, B](either: Either[Expr[A], Expr[B]]) extends Expr[Either[A, B]] {
    def schema: Schema[_ <: Either[_ <: A, _ <: B]] = either match {
      case Left(value)  => Schema.schemaEither(value.schema, Schema.fail[B]("Must be left"))
      case Right(value) => Schema.schemaEither(Schema.fail[A]("Must be right"), value.schema)
    }
  }

  final case class FoldEither[A, B, C](either: Expr[Either[A, B]], left: Expr[A] => Expr[C], right: Expr[B] => Expr[C])
      extends Expr[C] {
    def schema = ???
  }

  final case class Tuple2[A, B](left: Expr[A], right: Expr[B]) extends Expr[(A, B)] {
    def schema: Schema[_ <: (_ <: A, _ <: B)] =
      Schema.schemaTuple2(left.schema, right.schema)
  }

  final case class Tuple3[A, B, C](_1: Expr[A], _2: Expr[B], _3: Expr[C]) extends Expr[(A, B, C)] {
    def schema: Schema[_ <: (_ <: A, _ <: B, _ <: C)] =
      Schema.schemaTuple3(_1.schema, _2.schema, _3.schema)
  }

  final case class First[A, B](tuple: Expr[(A, B)]) extends Expr[A] {
    def schema: Schema[_ <: A] = ??? // FIXME: ZIO Schema
  }

  final case class Second[A, B](tuple: Expr[(A, B)]) extends Expr[B] {
    def schema: Schema[_ <: B] = ??? // FIXME: ZIO Schema
  }

  final case class Branch[A](predicate: Expr[Boolean], ifTrue: Expr[A], ifFalse: Expr[A]) extends Expr[A] {
    def schema = ifTrue.schema // FIXME: Schema fallback
  }

  final case class LessThanEqual[A](left: Expr[A], right: Expr[A], sortable: Sortable[A]) extends Expr[Boolean] {
    def schema: Schema[Boolean] = Schema[Boolean] // FIXME: Schema fallback
  }

  final case class Not[A](value: Expr[Boolean]) extends Expr[Boolean] {
    def schema: Schema[Boolean] = Schema[Boolean]
  }

  final case class And[A](left: Expr[Boolean], right: Expr[Boolean]) extends Expr[Boolean] {
    def schema: Schema[Boolean] = Schema[Boolean]
  }

  final case class Fold[A, B](list: Expr[List[A]], initial: Expr[B], body: Expr[(B, A)] => Expr[B]) extends Expr[B] {

    def schema = initial.schema // FIXME: There can be schemas for other B's
  }

  final case class LongInstant[A](instant: Expr[Instant], temporalUnit: Expr[TemporalUnit]) extends Expr[Long] {
    def schema = ???
  }

  final case class IsAfter[A](first: Expr[Instant], second: Expr[Instant]) extends Expr[Boolean] {
    def schema = ???
  }

  final case class PlusDuration[A](first: Expr[Duration], second: Expr[Duration]) extends Expr[Duration] {
    def schema = ???
  }

  final case class MinusDuration[A](first: Expr[Duration], second: Expr[Duration]) extends Expr[Duration] {
    def schema = ???
  }

  final case class LongDuration[A](duration: Expr[Duration], temporalUnit: Expr[TemporalUnit]) extends Expr[Long] {
    def schema = ???
  }

  final case class Iterate[A](initial: Expr[A], iterate: Expr[A] => Expr[A], predicate: Expr[A] => Expr[Boolean])
      extends Expr[A] {
    def schema = initial.schema // FIXME: There can be schemas for other A's
  }

  final case class Lazy[A] private (value: () => Expr[A]) extends Expr[A] {
    def schema: Schema[_ <: A] = value().schema
  }

  object Lazy {
    def apply[A](value: () => Expr[A]): Expr[A] = {
      lazy val expr = value()

      val value2: () => Expr[A] = () => expr

      new Lazy(value2)
    }
  }

  implicit def apply[A: Schema](value: A): Expr[A] =
    Literal(value, Schema[A])

  def checkEquality[A](self: Expr[A], that: Expr[Any]): Boolean = {
    var counter: Int = 0

    def freshIdentifier(): String = {
      counter = counter + 1

      s"var${counter}"
    }

    def loop[A](self: Expr[A], that: Expr[Any]): Boolean =
      (self, that) match {
        case (Literal(value1, schema1), Literal(value2, schema2)) =>
          // TODO: Ensure ZIO Schema supports `==` and `hashCode`.
          (value1 == value2) && (schema1 == schema2)

        case (l: Ignore[l], Ignore(value2)) =>
          loop(l.value, value2)

        case (Variable(identifier1, schema1), Variable(identifier2, schema2)) =>
          (identifier1 == identifier2) && (schema1 == schema2)

        case (AddNumeric(left1, right1, numeric1), AddNumeric(left2, right2, numeric2)) =>
          loop(left1, left2) &&
            loop(right1, right1) &&
            (numeric1 == numeric2)

        case (DivNumeric(left1, right1, numeric1), DivNumeric(left2, right2, numeric2)) =>
          loop(left1, left2) &&
            loop(right1, right1) &&
            (numeric1 == numeric2)

        case (MulNumeric(left1, right1, numeric1), MulNumeric(left2, right2, numeric2)) =>
          loop(left1, left2) &&
            loop(right1, right1) &&
            (numeric1 == numeric2)

        case (PowNumeric(left1, right1, numeric1), PowNumeric(left2, right2, numeric2)) =>
          loop(left1, left2) &&
            loop(right1, right1) &&
            (numeric1 == numeric2)

        case (NegationNumeric(value1, numeric1), NegationNumeric(value2, numeric2)) =>
          loop(value1, value2) && (numeric1 == numeric2)

        case (l: Either0[l1, l2], Either0(either2)) =>
          (l.either, either2) match {
            case (Left(l), Left(r))   => loop(l, r)
            case (Right(l), Right(r)) => loop(l, r)
            case _                    => false
          }

        case (FoldEither(either1, left1, right1), FoldEither(either2, left2, right2)) =>
          // TODO: FoldEither must capture Schema[C]
          val leftId  = Variable(freshIdentifier(), ???)
          val rightId = Variable(freshIdentifier(), ???)

          loop(either1, either2) &&
          loop(left1(leftId), left2(leftId)) &&
          loop(right1(rightId), right2(rightId))

        case (l: Tuple2[l1, l2], Tuple2(left2, right2)) =>
          loop(l.left, left2) && loop(l.right, right2)

        case (l: Tuple3[l1, l2, l3], Tuple3(a2, b2, c2)) =>
          loop(l._1, a2) &&
            loop(l._2, b2) &&
            loop(l._3, c2)

        case (First(tuple1), First(tuple2)) =>
          loop(tuple1, tuple2)

        case (Second(tuple1), Second(tuple2)) =>
          loop(tuple1, tuple2)

        case (Branch(predicate1, ifTrue1, ifFalse1), Branch(predicate2, ifTrue2, ifFalse2)) =>
          loop(predicate1, predicate2) &&
            loop(ifTrue1, ifTrue2) &&
            loop(ifFalse2, ifFalse2)

        case (l: LessThanEqual[l], LessThanEqual(left2, right2, sortable2)) =>
          // TODO: Support `==` and `hashCode` for `Sortable`.
          loop(l.left, left2) &&
            loop(l.right, right2) &&
            l.sortable == sortable2

        case (l: Not[l], Not(value2)) =>
          loop(l.value, value2)

        case (l: And[l], And(left2, right2)) =>
          loop(l.left, left2) && loop(l.right, right2)

        case (Fold(list1, initial1, body1), Fold(list2, initial2, body2)) =>
          // TODO: Need Schema[(B, A)]
          // Fold can capture Schema[A] (???), and we already have Schema[B]
          // We can use these to make Schema[(B, A)]
          val identifier = Variable(freshIdentifier(), ???)

          loop(list1, list2) &&
          loop(initial1, initial2) &&
          loop(body1(identifier), body2(identifier))

        case (Iterate(initial1, iterate1, predicate1), Iterate(initial2, iterate2, predicate2)) =>
          val var1 = Variable(freshIdentifier(), ???)
          val var2 = Variable(freshIdentifier(), ???)

          loop(initial1, initial2) &&
          loop(iterate1(var1), iterate2(var1)) &&
          loop(predicate1(var2), predicate2(var2))

        case (Lazy(value1), Lazy(value2)) =>
          // TODO: Handle loops in the graph appropriately
          loop(value1(), value2())

        case _ => false
      }

    loop(self, that)
  }

  implicit def tuple2[A, B](t: (Expr[A], Expr[B])): Expr[(A, B)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[A, B, C](t: (Expr[A], Expr[B], Expr[C])): Expr[(A, B, C)] =
    Tuple3(t._1, t._2, t._3)

  implicit def either[A, B](either0: Either[Expr[A], Expr[B]]): Expr[Either[A, B]] =
    Either0(either0)

  def suspend[A](expr: => Expr[A]): Expr[A] = Lazy(() => expr)

  implicit def toFlow[A](expr: Expr[A]): ZFlow[Any, Nothing, A] = expr.toFlow

  val unit: Expr[Unit] = Expr(())

  implicit def schemaExpr[A]: Schema[Expr[A]] = ???
}
