package zio.flow

import java.time.temporal.{ ChronoUnit, TemporalUnit }
import java.time.{ Duration, Instant, Period }

import scala.language.implicitConversions

// TODO: Replace by ZIO Schema
trait Schema[A]

object Schema {
  def apply[A](implicit schema: Schema[A]): Schema[A] = schema

  // FIXME: Add this to ZIO Schema
  def fail[A](message: String): Schema[A] = {
    val _ = message
    null
  }

  final case class SchemaTuple2[A: Schema, B: Schema]() extends Schema[(A, B)] {
    def leftSchema: Schema[A] = Schema[A]

    def rightSchema: Schema[B] = Schema[B]
  }

  implicit def nilSchema: Schema[Nil.type] = ???

  implicit def listSchema[A: Schema]: Schema[List[A]] = ???

  implicit def stringSchema: Schema[String] = Schema.fail("")

  implicit def shortSchema: Schema[Short] = Schema.fail("")

  implicit def intSchema: Schema[Int] = Schema.fail("Failed Int")

  implicit def longSchema: Schema[Long] = Schema.fail("")

  implicit def floatSchema: Schema[Float] = Schema.fail("")

  implicit def doubleSchema: Schema[Double] = Schema.fail("")

  implicit def bigIntSchema: Schema[BigInt] = Schema.fail("")

  implicit def bigDecimalSchema: Schema[BigDecimal] = Schema.fail("")

  implicit def unitSchema: Schema[Unit] = Schema.fail("")

  implicit def boolSchema: Schema[Boolean] = Schema.fail("")

  implicit def leftSchema[A: Schema]: Schema[Left[A, Nothing]] = ???

  implicit def rightSchema[B: Schema]: Schema[Right[Nothing, B]] = ???

  implicit def schemaTuple2[A: Schema, B: Schema]: Schema[(A, B)] = Schema.fail("")

  implicit def schemaTuple3[A: Schema, B: Schema, C: Schema]: Schema[(A, B, C)] = Schema.fail("")

  implicit def schemaTuple4[A: Schema, B: Schema, C: Schema, D: Schema]: Schema[(A, B, C, D)] = ???

  implicit def schemaEither[A: Schema, B: Schema]: Schema[Either[A, B]] = ???

  implicit def schemaNothing: Schema[Nothing] = ???

  implicit def chronoUnitSchema: Schema[ChronoUnit] = ???

  implicit def temporalUnitSchema: Schema[TemporalUnit] = ???

  implicit def noneSchema: Schema[None.type] = ???

  implicit def optionSchema[A]: Schema[Option[A]] = ???

  implicit def instantSchema: Schema[Instant] = ???

  implicit def durationSchema: Schema[Duration] = ???

  implicit def periodSchema: Schema[Period] = ???
}

/**
 * A `Remote[A]` is a blueprint for constructing a value of type `A` on a
 * remote machine. Remote values can always be serialized, because they are
 * mere blueprints, and they do not contain any Scala code.
 */
sealed trait Remote[+A]
    extends RemoteRelational[A]
    with RemoteBoolean[A]
    with RemoteTuple[A]
    with RemoteList[A]
    with RemoteNumeric[A]
    with RemoteEither[A]
    with RemoteFractional[A]
    with RemoteInstant[A]
    with RemoteOption[A]
    with RemoteDuration[A]
    with RemoteExecutingFlow[A] {
  def eval: Either[Remote[A], A]

  def self: Remote[A] = this

  final def iterate[A1 >: A](step: Remote[A1] => Remote[A1])(predicate: Remote[A1] => Remote[Boolean]): Remote[A1] =
    predicate(self).ifThenElse(
      step(self).iterate(step)(predicate),
      self
    )

  final def toFlow: ZFlow[Any, Nothing, A] = ZFlow(self)

  final def widen[B](implicit ev: A <:< B): Remote[B] = {
    val _ = ev

    self.asInstanceOf[Remote[B]]
  }

  final def unit: Remote[Unit] = Remote.Ignore(self)

  override def equals(that: Any): Boolean =
    that match {
      case that: Remote[a] => Remote.checkEquality(self, that)
      case _               => false
    }
}

object Remote {

  final case class Literal[A](value: A, schema: Schema[A]) extends Remote[A] {
    def evalWithSchema: Either[Remote[A], (Schema[A], A)] =
      Right((schema, value))

    override def eval: Either[Remote[A], A] = Right(value)
  }

  final case class Ignore[A](value: Remote[A]) extends Remote[Unit] {
    def evalWithSchema: Either[Remote[Unit], (Schema[Unit], Unit)] =
      Right((Schema[Unit], ()))

    override def eval: Either[Remote[Unit], Unit] = Right(())

    def schema: Schema[Unit] = Schema[Unit]
  }

  final case class Variable[A](identifier: String, schema: Schema[A]) extends Remote[A] {
    def evalWithSchema: Either[Remote[A], (Schema[A], A)] = Left(self)

    override def eval: Either[Remote[A], A] = Left(self)
  }

  final case class AddNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    def evalWithSchema: Either[Remote[A], (Schema[A], A)] =
      Remote.binaryEval(left, right)((l, r) => (numeric.schema, numeric.add(l, r)), AddNumeric(_, _, numeric))

    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(left, right)(numeric.add, AddNumeric(_, _, numeric))
  }

  final case class RemoteFunction[A, B](fn: Remote[A] => Remote[B]) extends Remote[A => B] {
    def evalWithSchema: Either[Remote[A => B], (Schema[A => B], A => B)] = Left(this)

    // TODO: Actually eval?
    override def eval: Either[Remote[A => B], A => B] = Left(this)
  }

  final case class RemoteApply[A, B](fn: Remote[A => B], a: Remote[A]) extends Remote[B] {
    override def eval: Either[Remote[B], B] = {
      val leftEither  = fn.eval
      val rightEither = a.eval

      (for {
        l <- leftEither
        r <- rightEither
      } yield l(r)) match {
        case Left(_)  =>
          val reducedLeft  = leftEither.fold(identity, _ => fn)
          val reducedRight = rightEither.fold(identity, _ => a)

          Left(RemoteApply(reducedLeft, reducedRight))
        case Right(v) => Right(v)
      }
    }
  }

  final case class DivNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(left, right)(numeric.divide, DivNumeric(_, _, numeric))
  }

  final case class MulNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(left, right)(numeric.multiply, MulNumeric(_, _, numeric))
  }

  final case class PowNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(left, right)(numeric.pow, PowNumeric(_, _, numeric))
  }

  final case class NegationNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] =
      Remote.unaryEval(value)(numeric.negate, NegationNumeric(_, numeric))
  }

  final case class RootNumeric[A](value: Remote[A], n: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(value, n)(numeric.root, RootNumeric(_, _, numeric))
  }

  final case class LogNumeric[A](value: Remote[A], base: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(value, base)(numeric.log, PowNumeric(_, _, numeric))
  }

  final case class SinFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] =
      Remote.unaryEval(value)(fractional.sin, SinFractional(_, fractional))
  }

  final case class SinInverseFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] =
      Remote.unaryEval(value)(fractional.inverseSin, SinInverseFractional(_, fractional))
  }

  final case class Either0[A, B](either: Either[Remote[A], Remote[B]]) extends Remote[Either[A, B]] {
    override def eval: Either[Remote[Either[A, B]], Either[A, B]] =
      either match {
        case Left(remoteA)  => remoteA.eval.fold(remoteA => Left(Either0(Left(remoteA))), a => Right(Left(a)))
        case Right(remoteB) => remoteB.eval.fold(remoteB => Left(Either0(Right(remoteB))), b => Right(Right(b)))
      }
  }

  final case class FoldEither[A, B, C](
    either: Remote[Either[A, B]],
    left: Remote[A] => Remote[C],
    right: Remote[B] => Remote[C]
  ) extends Remote[C] {
    override def eval: Either[Remote[C], C] =
      either.eval match {
        case Left(_) => Left(self)

        case Right(Left(a)) =>
          left(Literal(a, Schema.fail[A]("No schema for A"))).eval

        case Right(Right(b)) =>
          right(Literal(b, Schema.fail[B]("No schema for B"))).eval
      }
  }

  final case class Tuple2[A, B](left: Remote[A], right: Remote[B]) extends Remote[(A, B)] {
    override def eval: Either[Remote[(A, B)], (A, B)] =
      binaryEval(left, right)((a, b) => (a, b), (remoteA, remoteB) => Tuple2(remoteA, remoteB))
  }

  final case class Tuple3[A, B, C](_1: Remote[A], _2: Remote[B], _3: Remote[C]) extends Remote[(A, B, C)] {
    override def eval: Either[Remote[(A, B, C)], (A, B, C)] = {
      val first  = _1.eval
      val second = _2.eval
      val third  = _3.eval
      (for {
        a <- first
        b <- second
        c <- third
      } yield (a, b, c)) match {
        case Left(_)  =>
          val reducedFirst  = first.fold(identity, Literal(_, Schema.fail[A]("No schema for A")))
          val reducedSecond = second.fold(identity, Literal(_, Schema.fail[B]("No schema for B")))
          val reducedThird  = third.fold(identity, Literal(_, Schema.fail[C]("No schema for C")))
          Left((reducedFirst, reducedSecond, reducedThird))
        case Right(v) => Right(v)
      }
    }
  }

  final case class Tuple4[A, B, C, D](_1: Remote[A], _2: Remote[B], _3: Remote[C], _4: Remote[D])
      extends Remote[(A, B, C, D)] {
    override def eval: Either[Remote[(A, B, C, D)], (A, B, C, D)] = {
      val first  = _1.eval
      val second = _2.eval
      val third  = _3.eval
      val fourth = _4.eval
      (for {
        a <- first
        b <- second
        c <- third
        d <- fourth
      } yield (a, b, c, d)) match {
        case Left(_)  =>
          val reducedFirst  = first.fold(identity, Literal(_, Schema.fail[A]("No schema for A")))
          val reducedSecond = second.fold(identity, Literal(_, Schema.fail[B]("No schema for B")))
          val reducedThird  = third.fold(identity, Literal(_, Schema.fail[C]("No schema for C")))
          val reducedFourth = fourth.fold(identity, Literal(_, Schema.fail[D]("No schema for D")))
          Left((reducedFirst, reducedSecond, reducedThird, reducedFourth))
        case Right(v) => Right(v)
      }
    }
  }

  final case class First[A, B](tuple: Remote[(A, B)]) extends Remote[A] {
    override def eval: Either[Remote[A], A] = unaryEval(tuple)(t => t._1, remoteT => First(remoteT))
  }

  final case class Second[A, B](tuple: Remote[(A, B)]) extends Remote[B] {
    override def eval: Either[Remote[B], B] = unaryEval(tuple)(t => t._2, remoteT => Second(remoteT))
  }

  final case class Branch[A](predicate: Remote[Boolean], ifTrue: Remote[A], ifFalse: Remote[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] = predicate.eval match {
      case Left(_)      => Left(self)
      case Right(value) => if (value) ifTrue.eval else ifFalse.eval
    }
  }

  // def eval: Either[Remote[Boolean], Boolean]
  // def eval: Either[Remote[A], (Schema[A], A)]
  final case class LessThanEqual[A](left: Remote[A], right: Remote[A]) extends Remote[Boolean] {
    override def eval: Either[Remote[Boolean], Boolean] =
      // FIXME: Compare two values of type A
      // NOTE: Can do this when `evalWithSchema` is done!
      binaryEval(left, right)((_, _) => ???, (rL, rR) => LessThanEqual(rL, rR))
  }

  final case class Not[A](value: Remote[Boolean]) extends Remote[Boolean] {
    override def eval: Either[Remote[Boolean], Boolean] = unaryEval(value)(a => !a, remoteA => Not(remoteA))
  }

  final case class And[A](left: Remote[Boolean], right: Remote[Boolean]) extends Remote[Boolean] {
    override def eval: Either[Remote[Boolean], Boolean] =
      binaryEval(left, right)((l, r) => l && r, (remoteL, remoteR) => And(remoteL, remoteR))
  }

  final case class Fold[A, B](list: Remote[List[A]], initial: Remote[B], body: Remote[(B, A)] => Remote[B])
      extends Remote[B] {
    override def eval: Either[Remote[B], B] = list.eval match {
      case Left(_)  => Left(self)
      case Right(l) =>
        l.foldLeft[Either[Remote[B], B]](initial.eval) {
          case (Left(_), _)  => Left(self)
          case (Right(b), a) => body(Literal((b, a), Schema.fail[(B, A)]("No schema for (B,A)"))).eval
        }
    }
  }

  final case class Cons[A](list: Remote[List[A]], head: Remote[A]) extends Remote[List[A]] {
    override def eval: Either[Remote[List[A]], List[A]] =
      binaryEval(list, head)((l, h) => h :: l, (remoteL, remoteH) => Cons(remoteL, remoteH))
  }

  final case class UnCons[A](list: Remote[List[A]]) extends Remote[Option[(A, List[A])]] {
    override def eval: Either[Remote[Option[(A, List[A])]], Option[(A, List[A])]] = unaryEval(list)(
      l =>
        l.headOption match {
          case scala.Some(v) => Some((v, l.tail))
          case None          => None
        },
      remoteList => UnCons(remoteList)
    )
  }

  final case class InstantFromLong[A](seconds: Remote[Long]) extends Remote[Instant] {
    override def eval: Either[Remote[Instant], Instant] =
      unaryEval(seconds)(s => Instant.ofEpochSecond(s), remoteS => InstantFromLong(remoteS))
  }

  final case class InstantToLong[A](instant: Remote[Instant]) extends Remote[Long] {
    override def eval: Either[Remote[Long], Long] =
      unaryEval(instant)(_.toEpochMilli, remoteS => InstantToLong(remoteS))
  }

  final case class DurationToLong[A](duration: Remote[Duration], temporalUnit: Remote[TemporalUnit])
      extends Remote[Long] {
    override def eval: Either[Remote[Long], Long] = binaryEval(duration, temporalUnit)(
      (d, tUnit) => d.get(tUnit),
      (remoteDuration, remoteUnit) => DurationToLong(remoteDuration, remoteUnit)
    )
  }

  final case class LongToDuration(seconds: Remote[Long]) extends Remote[Duration] {
    override def eval: Either[Remote[Duration], Duration] =
      unaryEval(seconds)(Duration.ofSeconds, remoteS => LongToDuration(remoteS))
  }

  final case class Iterate[A](
    initial: Remote[A],
    iterate: Remote[A] => Remote[A],
    predicate: Remote[A] => Remote[Boolean]
  ) extends Remote[A] {
    override def eval: Either[Remote[A], A] = {
      def loop(current: Remote[A]): Either[Remote[A], A] =
        predicate(current).eval match {
          case Left(_)      => Left(self)
          case Right(value) => if (value) loop(iterate(current)) else current.eval
        }
      loop(initial)
    }
  }

  final case class Lazy[A] private (value: () => Remote[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] = Left(self)
  }

  final case class Some0[A](value: Remote[A]) extends Remote[Option[A]] {
    override def eval: Either[Remote[Option[A]], Option[A]] = unaryEval(value)(a => Some(a), remoteA => Some0(remoteA))
  }

  final case class FoldOption[A, B](option: Remote[Option[A]], none: Remote[B], f: Remote[A] => Remote[B])
      extends Remote[B] {
    override def eval: Either[Remote[B], B] = option.eval match {
      case Left(_)   => Left(self)
      case Right(op) => op.fold(none.eval)(v => f(Literal(v, Schema.fail[A]("No schema for B"))).eval)
    }
  }

  object Lazy {
    def apply[A](value: () => Remote[A]): Remote[A] = {
      lazy val remote = value()

      val value2: () => Remote[A] = () => remote

      new Lazy(value2)
    }
  }

  private[zio] def unaryEval[A, B](
    remote: Remote[A]
  )(f: A => B, g: Remote[A] => Remote[B]): Either[Remote[B], B] =
    remote.eval.fold(remote => Left(g(remote)), a => Right(f(a)))

  private[zio] def binaryEval[A, B, C, D](
    left: Remote[A],
    right: Remote[B]
  )(f: (A, B) => D, g: (Remote[A], Remote[B]) => Remote[C]): Either[Remote[C], D] = {
    val leftEither  = left.eval
    val rightEither = right.eval

    (for {
      l <- leftEither
      r <- rightEither
    } yield f(l, r)) match {
      case Left(_)  =>
        Left(g(left, right))
      case Right(v) => Right(v)
    }
  }

  implicit def apply[A: Schema](value: A): Remote[A] =
    Literal(value, Schema[A])

  def checkEquality[A](self: Remote[A], that: Remote[Any]): Boolean = {
    var counter: Int = 0

    def freshIdentifier(): String = {
      counter = counter + 1

      s"var${counter}"
    }

    def loop[A](self: Remote[A], that: Remote[Any]): Boolean =
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

        case (l: LessThanEqual[l], LessThanEqual(left2, right2)) =>
          // TODO: Support `==` and `hashCode` for `Sortable`.
          loop(l.left, left2) &&
            loop(l.right, right2)

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

  implicit def either[A, B](either0: Either[Remote[A], Remote[B]]): Remote[Either[A, B]] =
    Either0(either0)

  def fromEpochSec(seconds: Remote[Long]): Remote[Instant] =
    Remote.InstantFromLong(seconds)

  def let[A, B](remote: Remote[A])(fn: Remote[A] => Remote[B]): Remote[B] =
    Remote.RemoteApply(Remote.RemoteFunction(fn), remote)

  def ofSeconds(seconds: Remote[Long]): Remote[Duration] = Remote.LongToDuration(seconds)

  def ofMinutes(minutes: Remote[Long]): Remote[Duration] = Remote.ofSeconds(minutes * Remote(60))

  def ofHours(hours: Remote[Long]): Remote[Duration] = Remote.ofMinutes(hours * Remote(60))

  def ofDays(days: Remote[Long]): Remote[Duration] = Remote.ofHours(days * Remote(24))

  implicit def tuple2[A, B](t: (Remote[A], Remote[B])): Remote[(A, B)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[A, B, C](t: (Remote[A], Remote[B], Remote[C])): Remote[(A, B, C)] =
    Tuple3(t._1, t._2, t._3)

  implicit def tuple4[A, B, C, D](t: (Remote[A], Remote[B], Remote[C], Remote[D])): Remote[(A, B, C, D)] =
    Tuple4(t._1, t._2, t._3, t._4)

  def suspend[A](remote: Remote[A]): Remote[A] = Lazy(() => remote)

  implicit def toFlow[A](remote: Remote[A]): ZFlow[Any, Nothing, A] = remote.toFlow

  val unit: Remote[Unit] = Remote(())

  implicit def schemaRemote[A]: Schema[Remote[A]] = ???

}
