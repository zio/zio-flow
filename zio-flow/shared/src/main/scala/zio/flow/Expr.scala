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
    def leftSchema: Schema[A] = Schema[A]

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

  implicit def noneSchema: Schema[None.type] = ???

  implicit def optionSchema[A]: Schema[Option[A]] = ???
}

sealed trait Expr[+A]
    extends ExprSortable[A]
    with ExprBoolean[A]
    with ExprTuple[A]
    with ExprList[A]
    with ExprNumeric[A]
    with ExprFractional[A]
    with ExprInstant[A]
    with ExprOption[A]
    with ExprDuration[A] {
  def eval: Either[Expr[A], A] = ???

  def self: Expr[A] = this

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

  final case class Variable[A](identifier: String, schema: Schema[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = Left(self)
  }

  final case class AddNumeric[A](left: Expr[A], right: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = numeric.schema

      Expr.binaryEval(left, right)(numeric.add(_, _), AddNumeric(_, _, numeric))
    }
  }

  final case class ExprFunction[A, B](fn: Expr[A] => Expr[B]) extends Expr[A => B] {
    override def eval: Either[Expr[A => B], A => B] = Left(this)
  }

  final case class ExprApply[A, B](fn: Expr[A => B], a: Expr[A]) extends Expr[B] {
    override def eval: Either[Expr[B], B] = {
      val leftEither  = fn.eval
      val rightEither = a.eval

      (for {
        l <- leftEither
        r <- rightEither
      } yield l(r)) match {
        case Left(_)  =>
          val reducedLeft  = leftEither.fold(identity, _ => fn)
          val reducedRight = rightEither.fold(identity, _ => a)

          Left(ExprApply(reducedLeft, reducedRight))
        case Right(v) => Right(v)
      }
    }
  }

  final case class DivNumeric[A](left: Expr[A], right: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = numeric.schema

      Expr.binaryEval(left, right)(numeric.divide(_, _), DivNumeric(_, _, numeric))
    }
  }

  final case class MulNumeric[A](left: Expr[A], right: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = numeric.schema

      Expr.binaryEval(left, right)(numeric.multiply(_, _), MulNumeric(_, _, numeric))
    }
  }

  final case class PowNumeric[A](left: Expr[A], right: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = numeric.schema

      Expr.binaryEval(left, right)(numeric.pow(_, _), PowNumeric(_, _, numeric))
    }
  }

  final case class NegationNumeric[A](value: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = numeric.schema

      Expr.unaryEval(value)(numeric.negate(_), NegationNumeric(_, numeric))
    }
  }

  final case class RootNumeric[A](value: Expr[A], n: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = numeric.schema

      Expr.binaryEval(value, n)(numeric.root(_, _), RootNumeric(_, _, numeric))
    }
  }

  final case class LogNumeric[A](value: Expr[A], base: Expr[A], numeric: Numeric[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = numeric.schema

      Expr.binaryEval(value, base)(numeric.log(_, _), PowNumeric(_, _, numeric))
    }
  }

  final case class SinFractional[A](value: Expr[A], fractional: Fractional[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = fractional.schema

      Expr.unaryEval(value)(fractional.sin(_), SinFractional(_, fractional))
    }
  }

  final case class SinInverseFractional[A](value: Expr[A], fractional: Fractional[A]) extends Expr[A] {
    override def eval: Either[Expr[A], A] = {
      implicit val schemaA = fractional.schema

      Expr.unaryEval(value)(fractional.inverseSin(_), SinInverseFractional(_, fractional))
    }
  }

  final case class Either0[A, B](either: Either[Expr[A], Expr[B]]) extends Expr[Either[A, B]] {
    override def eval: Either[Expr[Either[A, B]], Either[A, B]] =
      either match {
        case Left(exprA)  => exprA.eval.fold(exprA => Left(Either0(Left(exprA))), a => Right(Left(a)))
        case Right(exprB) => exprB.eval.fold(exprB => Left(Either0(Right(exprB))), b => Right(Right(b)))
      }
  }

  final case class FoldEither[A, B, C](either: Expr[Either[A, B]], left: Expr[A] => Expr[C], right: Expr[B] => Expr[C])
      extends Expr[C] {
    override def eval: Either[Expr[C], C] =
      either.eval match {
        case Left(exprEither) => Left(FoldEither(exprEither, left, right))

        case Right(Left(a)) =>
          implicit val schemaA: Schema[A] = null.asInstanceOf[Schema[A]]

          left(Expr(a)).eval

        case Right(Right(b)) =>
          implicit val schemaB: Schema[B] = null.asInstanceOf[Schema[B]]

          right(Expr(b)).eval

      }
  }

  final case class Tuple2[A, B](left: Expr[A], right: Expr[B]) extends Expr[(A, B)]

  final case class Tuple3[A, B, C](_1: Expr[A], _2: Expr[B], _3: Expr[C]) extends Expr[(A, B, C)]

  final case class First[A, B](tuple: Expr[(A, B)]) extends Expr[A]

  final case class Second[A, B](tuple: Expr[(A, B)]) extends Expr[B]

  final case class Branch[A](predicate: Expr[Boolean], ifTrue: Expr[A], ifFalse: Expr[A]) extends Expr[A]

  final case class LessThanEqual[A](left: Expr[A], right: Expr[A], sortable: Sortable[A]) extends Expr[Boolean]

  final case class Not[A](value: Expr[Boolean]) extends Expr[Boolean]

  final case class And[A](left: Expr[Boolean], right: Expr[Boolean]) extends Expr[Boolean]

  final case class Fold[A, B](list: Expr[List[A]], initial: Expr[B], body: Expr[(B, A)] => Expr[B]) extends Expr[B]

  final case class Cons[A](list: Expr[List[A]], head: Expr[A]) extends Expr[List[A]]

  final case class UnCons[A](list: Expr[List[A]]) extends Expr[Option[(A, List[A])]]

  final case class InstantFromLong[A](seconds: Expr[Long]) extends Expr[Instant]

  final case class InstantToLong[A](instant: Expr[Instant], temporalUnit: Expr[TemporalUnit]) extends Expr[Long]

  final case class DurationToLong[A](duration: Expr[Duration], temporalUnit: Expr[TemporalUnit]) extends Expr[Long]

  final case class LongToDuration(seconds: Expr[Long]) extends Expr[Duration]

  final case class Iterate[A](initial: Expr[A], iterate: Expr[A] => Expr[A], predicate: Expr[A] => Expr[Boolean])
      extends Expr[A]

  final case class Lazy[A] private (value: () => Expr[A]) extends Expr[A]

  final case class Some[A](value: Expr[A]) extends Expr[Option[A]]

  final case class FoldOption[A, B](option: Expr[Option[A]], none: Expr[B], f: Expr[A] => Expr[B]) extends Expr[B]

  object Lazy {
    def apply[A](value: () => Expr[A]): Expr[A] = {
      lazy val expr = value()

      val value2: () => Expr[A] = () => expr

      new Lazy(value2)
    }
  }

  private[zio] def unaryEval[A: Schema, B](expr: Expr[A])(f: A => B, g: Expr[A] => Expr[B]): Either[Expr[B], B] =
    expr.eval.fold(expr => Left(g(expr)), a => Right(f(a)))

  private[zio] def binaryEval[A: Schema, B: Schema, C](
    left: Expr[A],
    right: Expr[B]
  )(f: (A, B) => C, g: (Expr[A], Expr[B]) => Expr[C]): Either[Expr[C], C] = {
    val leftEither  = left.eval
    val rightEither = right.eval

    (for {
      l <- leftEither
      r <- rightEither
    } yield f(l, r)) match {
      case Left(_)  =>
        val reducedLeft  = leftEither.fold(identity, Expr(_))
        val reducedRight = rightEither.fold(identity, Expr(_))

        Left(g(reducedLeft, reducedRight))
      case Right(v) => Right(v)
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

  implicit def either[A, B](either0: Either[Expr[A], Expr[B]]): Expr[Either[A, B]] =
    Either0(either0)

  def fromEpochSec(seconds: Expr[Long]): Expr[Instant] =
    Expr.InstantFromLong(seconds)

  def let[A, B](expr: Expr[A])(fn: Expr[A] => Expr[B]): Expr[B] =
    Expr.ExprApply(Expr.ExprFunction(fn), expr)

  def ofSeconds(seconds: Expr[Long]): Expr[Duration] = Expr.LongToDuration(seconds)

  def ofMinutes(minutes: Expr[Long]): Expr[Duration] = Expr.ofSeconds(minutes * Expr(60))

  def ofHours(hours: Expr[Long]): Expr[Duration] = Expr.ofMinutes(hours * Expr(60))

  def ofDays(days: Expr[Long]): Expr[Duration] = Expr.ofHours(days * Expr(24))

  implicit def tuple2[A, B](t: (Expr[A], Expr[B])): Expr[(A, B)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[A, B, C](t: (Expr[A], Expr[B], Expr[C])): Expr[(A, B, C)] =
    Tuple3(t._1, t._2, t._3)

  def suspend[A](expr: => Expr[A]): Expr[A] = Lazy(() => expr)

  implicit def toFlow[A](expr: Expr[A]): ZFlow[Any, Nothing, A] = expr.toFlow

  val unit: Expr[Unit] = Expr(())

  implicit def schemaExpr[A]: Schema[Expr[A]] = ???
}
