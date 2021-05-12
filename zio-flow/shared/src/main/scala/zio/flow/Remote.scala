package zio.flow

import java.time.temporal.TemporalUnit
import java.time.{ Duration, Instant }

import scala.language.implicitConversions

import zio.schema.Schema

trait SchemaAndValue[+A] {
  type Subtype <: A
  def schema: Schema[Subtype]
  def value: Subtype
}

object SchemaAndValue {
  def apply[A](schema0: Schema[A], value0: A): SchemaAndValue[A]                                                      =
    new SchemaAndValue[A] {
      override type Subtype = A

      override def schema: Schema[Subtype] = schema0

      override def value: Subtype = value0

      //TODO : Equals and Hashcode required
    }
  def unapply[A](schemaAndValue: SchemaAndValue[A]): Option[(Schema[schemaAndValue.Subtype], schemaAndValue.Subtype)] =
    Some((schemaAndValue.schema, schemaAndValue.value))
}

/**
 * A `Remote[A]` is a blueprint for constructing a value of type `A` on a
 * remote machine. Remote values can always be serialized, because they are
 * mere blueprints, and they do not contain any Scala code.
 */
sealed trait Remote[+A]
    extends RemoteRelational[A]
    with RemoteNumeric[A]
    with RemoteFractional[A]
    with RemoteExecutingFlow[A] {

  def eval: Either[Remote[A], A]

  def evalWithSchema: Either[Remote[A], SchemaAndValue[A]]

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
    def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Right(SchemaAndValue(schema, value))

    override def eval: Either[Remote[A], A] = Right(value)
  }

  final case class Ignore[A](value: Remote[A]) extends Remote[Unit] {
    def evalWithSchema: Either[Remote[Unit], SchemaAndValue[Unit]] =
      Right(SchemaAndValue(Schema[Unit], ()))

    override def eval: Either[Remote[Unit], Unit] = Right(())

    def schema: Schema[Unit] = Schema[Unit]
  }

  final case class Variable[A](identifier: String, schema: Schema[A]) extends Remote[A] {
    def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] = Left(self)

    override def eval: Either[Remote[A], A] = Left(self)
  }

  final case class AddNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.binaryEval(left, right)(
        (l, r) => SchemaAndValue(numeric.schema, numeric.add(l, r)),
        AddNumeric(_, _, numeric)
      )

    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(left, right)(numeric.add, AddNumeric(_, _, numeric))
  }

  final case class RemoteFunction[A, B](fn: Remote[A] => Remote[B]) extends Remote[A => B] {
    def evalWithSchema: Either[Remote[A => B], SchemaAndValue[A => B]] = Left(this)

    // TODO: Actually eval?
    override def eval: Either[Remote[A => B], A => B] = Left(this)
  }

  final case class RemoteApply[A, B](fn: Remote[A => B], a: Remote[A]) extends Remote[B] {

    override def evalWithSchema: Either[Remote[B], SchemaAndValue[B]] = {
      val aEval = a.evalWithSchema
      aEval match {
        case Left(_)               => Left(self)
        case Right(schemaAndValue) =>
          fn match {
            case RemoteFunction(fn) =>
              fn(Remote.Literal[schemaAndValue.Subtype](schemaAndValue.value, schemaAndValue.schema)).evalWithSchema
            case _                  => throw new IllegalStateException("Every remote function must be constructed using RemoteFunction.")
          }
      }
    }

    override def eval: Either[Remote[B], B] = evalWithSchema.map(_.value)
  }

  final case class DivNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.binaryEvalWithSchema(left, right)(numeric.divide, DivNumeric(_, _, numeric), numeric.schema)

    override def eval: Either[Remote[A], A] = evalWithSchema.map(_.value)
  }

  final case class MulNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.binaryEvalWithSchema(left, right)(numeric.multiply, MulNumeric(_, _, numeric), numeric.schema)

    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(left, right)(numeric.multiply, MulNumeric(_, _, numeric))
  }

  final case class PowNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.binaryEvalWithSchema(left, right)(numeric.pow, PowNumeric(_, _, numeric), numeric.schema)

    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(left, right)(numeric.pow, PowNumeric(_, _, numeric))
  }

  final case class NegationNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.unaryEvalWithSchema(value)(numeric.negate, NegationNumeric(_, numeric), numeric.schema)

    override def eval: Either[Remote[A], A] =
      Remote.unaryEval(value)(numeric.negate, NegationNumeric(_, numeric))
  }

  final case class RootNumeric[A](value: Remote[A], n: Remote[A], numeric: Numeric[A]) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.binaryEvalWithSchema(value, n)(numeric.root, RootNumeric(_, _, numeric), numeric.schema)

    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(value, n)(numeric.root, RootNumeric(_, _, numeric))
  }

  final case class LogNumeric[A](value: Remote[A], base: Remote[A], numeric: Numeric[A]) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.binaryEvalWithSchema(value, base)(numeric.log, PowNumeric(_, _, numeric), numeric.schema)

    override def eval: Either[Remote[A], A] =
      Remote.binaryEval(value, base)(numeric.log, PowNumeric(_, _, numeric))
  }

  final case class SinFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.unaryEvalWithSchema(value)(a => fractional.sin(a), SinFractional(_, fractional), fractional.schema)

    override def eval: Either[Remote[A], A] =
      Remote.unaryEval(value)(fractional.sin, SinFractional(_, fractional))
  }

  final case class SinInverseFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      Remote.unaryEvalWithSchema(value)(fractional.inverseSin, SinInverseFractional(_, fractional), fractional.schema)

    override def eval: Either[Remote[A], A] =
      Remote.unaryEval(value)(fractional.inverseSin, SinInverseFractional(_, fractional))
  }

  final case class Either0[A, B](either: Either[Remote[A], Remote[B]]) extends Remote[Either[A, B]] {
    //TODO : Is this a valid function
    def toLeftSchema[T, U](schema: Schema[T]): Schema[Either[T, U]]  = ???
    def toRightSchema[T, U](schema: Schema[T]): Schema[Either[U, T]] = ???

    override def evalWithSchema: Either[Remote[Either[A, B]], SchemaAndValue[Either[A, B]]] = either match {
      case Left(remoteA)  =>
        remoteA.evalWithSchema.fold(
          remoteA => Left(Either0(Left(remoteA))),
          a => Right(SchemaAndValue(toLeftSchema(a.schema), Left(a.value)))
        )
      case Right(remoteB) =>
        remoteB.evalWithSchema.fold(
          remoteB => Left(Either0(Right(remoteB))),
          b => Right(SchemaAndValue(toRightSchema(b.schema), (Right(b.value))))
        )
    }
    override def eval: Either[Remote[Either[A, B]], Either[A, B]]                           =
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
    override def evalWithSchema: Either[Remote[C], SchemaAndValue[C]] = either.evalWithSchema match {
      case Left(_)                              => Left(self)
      case Right(SchemaAndValue(schema, value)) =>
        val schemaEither = schema.asInstanceOf[Schema.EitherSchema[A, B]]
        value match {
          case Left(a)  => left(Literal(a, schemaEither.left)).evalWithSchema
          case Right(b) => right(Literal(b, schemaEither.right)).evalWithSchema
          case _        => throw new IllegalStateException("Every remote FoldEither must be constructed using Remote[Either].")
        }
      case Right(_)                             =>
        throw new IllegalStateException("Every remote FoldEither must be constructed using Remote[Either].")
    }

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

    override def evalWithSchema: Either[Remote[(A, B)], SchemaAndValue[(A, B)]] = {
      def schemaTuple[T, U](a: Schema[T], b: Schema[U]): Schema[(T, U)] = ???

      val evaluatedLeft  = left.evalWithSchema
      val evaluatedRight = right.evalWithSchema
      (for {
        l <- evaluatedLeft
        r <- evaluatedRight
      } yield (l, r)) match {
        case Left(_)       =>
          val reducedLeft  = evaluatedLeft.fold(identity, a => Literal(a.value, a.schema))
          val reducedRight = evaluatedRight.fold(identity, b => Literal(b.value, b.schema))
          Left((reducedLeft, reducedRight))
        case Right((a, b)) => Right(SchemaAndValue(schemaTuple(a.schema, b.schema), (a.value, b.value)))
      }
    }

    override def eval: Either[Remote[(A, B)], (A, B)] =
      binaryEval(left, right)((a, b) => (a, b), (remoteA, remoteB) => Tuple2(remoteA, remoteB))
  }

  final case class Tuple3[A, B, C](_1: Remote[A], _2: Remote[B], _3: Remote[C]) extends Remote[(A, B, C)] {

    override def evalWithSchema: Either[Remote[(A, B, C)], SchemaAndValue[(A, B, C)]] = {
      def schemaTuple[S, T, U](schemaS: Schema[S], schemaT: Schema[T], schemaU: Schema[U]): Schema[(S, T, U)] = ???

      val first  = _1.evalWithSchema
      val second = _2.evalWithSchema
      val third  = _3.evalWithSchema

      (for {
        a <- first
        b <- second
        c <- third
      } yield (a, b, c)) match {
        case Left(_)          =>
          val reducedFirst  = first.fold(identity, a => Literal(a.value, a.schema))
          val reducedSecond = second.fold(identity, b => Literal(b.value, b.schema))
          val reducedThird  = third.fold(identity, c => Literal(c.value, c.schema))
          Left((reducedFirst, reducedSecond, reducedThird))
        case Right((a, b, c)) =>
          Right(SchemaAndValue(schemaTuple(a.schema, b.schema, c.schema), (a.value, b.value, c.value)))
      }
    }

    override def eval: Either[Remote[(A, B, C)], (A, B, C)] = evalWithSchema.map(_.value)
  }

  final case class Tuple4[A, B, C, D](_1: Remote[A], _2: Remote[B], _3: Remote[C], _4: Remote[D])
      extends Remote[(A, B, C, D)] {

    override def evalWithSchema: Either[Remote[(A, B, C, D)], SchemaAndValue[(A, B, C, D)]] = {
      def schemaTuple[S, T, U, V](
        schemaS: Schema[S],
        schemaT: Schema[T],
        schemaU: Schema[U],
        schemaV: Schema[V]
      ): Schema[(S, T, U, V)] = ???

      val first  = _1.evalWithSchema
      val second = _2.evalWithSchema
      val third  = _3.evalWithSchema
      val fourth = _4.evalWithSchema
      (for {
        a <- first
        b <- second
        c <- third
        d <- fourth
      } yield (a, b, c, d)) match {
        case Left(_)             =>
          val reducedFirst  = first.fold(identity, a => Literal(a.value, a.schema))
          val reducedSecond = second.fold(identity, b => Literal(b.value, b.schema))
          val reducedThird  = third.fold(identity, c => Literal(c.value, c.schema))
          val reducedFourth = fourth.fold(identity, d => Literal(d.value, d.schema))
          Left((reducedFirst, reducedSecond, reducedThird, reducedFourth))
        case Right((a, b, c, d)) =>
          Right(
            SchemaAndValue(schemaTuple(a.schema, b.schema, c.schema, d.schema), (a.value, b.value, c.value, d.value))
          )
      }
    }
    override def eval: Either[Remote[(A, B, C, D)], (A, B, C, D)] = evalWithSchema.map(_.value)
  }

  final case class First[A, B](tuple: Remote[(A, B)]) extends Remote[A] {
    override def eval: Either[Remote[A], A] = unaryEval(tuple)(t => t._1, remoteT => First(remoteT))

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] =
      tuple.evalWithSchema.fold(
        remote => Left(remote._1),
        {
          case SchemaAndValue(schemaTuple2, value) =>
            val schema = schemaTuple2.asInstanceOf[Schema.Tuple[A, B]].left
            val (a, _) = value.asInstanceOf[scala.Tuple2[A, B]]
            Right(SchemaAndValue(schema, a))
          case _                                   => throw new IllegalStateException("Every remote First must be constructed using Remote[(A,B)].")
        }
      )
  }

  final case class Second[A, B](tuple: Remote[(A, B)]) extends Remote[B] {
    override def eval: Either[Remote[B], B]                           = unaryEval(tuple)(t => t._2, remoteT => Second(remoteT))
    override def evalWithSchema: Either[Remote[B], SchemaAndValue[B]] =
      tuple.evalWithSchema.fold(
        remote => Left(remote._2),
        {
          case SchemaAndValue(schemaTuple2, value) =>
            val schema = schemaTuple2.asInstanceOf[Schema.Tuple[A, B]].right
            val (_, b) = value.asInstanceOf[scala.Tuple2[A, B]]
            Right(SchemaAndValue(schema, b))
          case _                                   => throw new IllegalStateException("Every remote FoldEither must be constructed using Remote[Either].")
        }
      )
  }

  final case class Branch[A](predicate: Remote[Boolean], ifTrue: Remote[A], ifFalse: Remote[A]) extends Remote[A] {
    override def eval: Either[Remote[A], A] = predicate.eval match {
      case Left(_)      => Left(self)
      case Right(value) => if (value) ifTrue.eval else ifFalse.eval
    }

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] = predicate.eval match {
      case Left(_)      => Left(self)
      case Right(value) => if (value) ifTrue.evalWithSchema else ifFalse.evalWithSchema
    }
  }

  // def eval: Either[Remote[Boolean], Boolean]
  // def eval: Either[Remote[A], (Schema[A], A)]
  final case class LessThanEqual[A](left: Remote[A], right: Remote[A]) extends Remote[Boolean] {
    override def evalWithSchema: Either[Remote[Boolean], SchemaAndValue[Boolean]] = {
      val lEval = left.evalWithSchema
      val rEval = right.evalWithSchema
      (lEval, rEval) match {
        //FIXME : fix when zio schema can compare Schemas
        case (Right(SchemaAndValue(leftSchemaA, leftA)), Right(SchemaAndValue(rightSchemaA, rightA))) =>
          Right(
            SchemaAndValue(Schema[Boolean], (leftA != rightA) && (leftSchemaA.hashCode() < rightSchemaA.hashCode()))
          )
        case _                                                                                        => Left(self)
      }
    }

    override def eval: Either[Remote[Boolean], Boolean] = evalWithSchema.map(_.value)
  }

  final case class Not[A](value: Remote[Boolean]) extends Remote[Boolean] {
    override def eval: Either[Remote[Boolean], Boolean] = unaryEval(value)(a => !a, remoteA => Not(remoteA))

    override def evalWithSchema: Either[Remote[Boolean], SchemaAndValue[Boolean]] =
      unaryEval(value)(a => !a, remoteA => Not(remoteA)).map(SchemaAndValue(Schema[Boolean], _))
  }

  final case class And[A](left: Remote[Boolean], right: Remote[Boolean]) extends Remote[Boolean] {
    override def eval: Either[Remote[Boolean], Boolean] =
      binaryEval(left, right)((l, r) => l && r, (remoteL, remoteR) => And(remoteL, remoteR))

    override def evalWithSchema: Either[Remote[Boolean], SchemaAndValue[Boolean]] =
      binaryEval(left, right)((l, r) => l && r, (remoteL, remoteR) => And(remoteL, remoteR))
        .map(SchemaAndValue(Schema[Boolean], _))
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

    def schemaTuple[S, T](s: Schema[S], t: Schema[T]): Schema[(S, T)] = ???

    override def evalWithSchema: Either[Remote[B], SchemaAndValue[B]] = list.evalWithSchema match {
      case Left(_)                              => Left(self)
      case Right(SchemaAndValue(schema, value)) =>
        val schemaList = schema.asInstanceOf[Schema[List[A]]]
        val list       = value.asInstanceOf[List[A]]
        list.foldLeft[Either[Remote[B], SchemaAndValue[B]]](initial.evalWithSchema) {
          case (Left(_), _)     => Left(self)
          case (Right(left), a) =>
            val schemaAndValue: SchemaAndValue[B] = left.asInstanceOf[SchemaAndValue[B]]
            schemaList match {
              case Schema.Sequence(schemaA, _, _) =>
                body(Literal((schemaAndValue.value, a), schemaTuple(schemaAndValue.schema, schemaA))).evalWithSchema
              case _                              =>
                throw new IllegalStateException(
                  "It should be possible to evaluate every remote Fold initial schema to a Schema[List]."
                )
            }
        }
      case _                                    => throw new IllegalStateException("Every remote Fold must be constructed using Remote[List].")
    }
  }

  final case class Cons[A](list: Remote[List[A]], head: Remote[A]) extends Remote[List[A]] {
    override def eval: Either[Remote[List[A]], List[A]] =
      binaryEval(list, head)((l, h) => h :: l, (remoteL, remoteH) => Cons(remoteL, remoteH))

    override def evalWithSchema: Either[Remote[List[A]], SchemaAndValue[List[A]]] = {
      val evaluatedList = list.evalWithSchema
      val evaluatedHead = head.evalWithSchema
      (for {
        l <- evaluatedList
        r <- evaluatedHead
      } yield (l, r)) match {
        case Left(_) =>
          val reducedList = evaluatedList.fold(identity, a => Literal(a.value, a.schema))
          val reducedHead = evaluatedHead.fold(identity, b => Literal(b.value, b.schema))
          Left(Cons(reducedList, reducedHead))

        case Right((aList, a)) =>
          Right(
            SchemaAndValue(
              aList.schema.asInstanceOf[Schema[List[A]]],
              a.value.asInstanceOf[A] :: aList.value.asInstanceOf[List[A]]
            )
          )
      }
    }
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

    override def evalWithSchema: Either[Remote[Option[(A, List[A])]], SchemaAndValue[Option[(A, List[A])]]] = {
      implicit def toOptionSchema[T](schema: Schema[T]): Schema[Option[T]]                     = ???
      implicit def toTupleSchema[S, U](schemaS: Schema[S], schemaU: Schema[U]): Schema[(S, U)] = ???

      list.evalWithSchema.fold(
        remote => Left(UnCons(remote)),
        rightVal => {
          val schemaAndValue = rightVal.asInstanceOf[SchemaAndValue[List[A]]]
          Right(schemaAndValue.value.headOption match {
            case Some(v) =>
              SchemaAndValue(
                toOptionSchema(
                  toTupleSchema(
                    schemaAndValue.schema.asInstanceOf[SchemaList[A]] match {
                      case Schema.Sequence(schemaA, _, _) => schemaA
                      case _                              => throw new IllegalStateException("UnCons should be constructed from Schema[List].")
                    },
                    schemaAndValue.schema.asInstanceOf[SchemaList[A]]
                  )
                ),
                Some((v.asInstanceOf[A], schemaAndValue.value.tail.asInstanceOf[List[A]]))
              )
            case None    =>
              val schema = schemaAndValue.schema.asInstanceOf[SchemaList[A]]
              SchemaAndValue(
                toOptionSchema(
                  toTupleSchema(
                    schema match {
                      case Schema.Sequence(schemaA, _, _) => schemaA
                      case _                              => throw new IllegalStateException("Uncons should be constructed from a Schema[List]")
                    },
                    schemaAndValue.schema
                  )
                ),
                None
              )
            case _       => throw new IllegalStateException("Every remote UnCons must be constructed using Remote[List].")
          })
        }
      )
    }
  }

  final case class InstantFromLong[A](seconds: Remote[Long]) extends Remote[Instant] {
    override def eval: Either[Remote[Instant], Instant] =
      unaryEval(seconds)(s => Instant.ofEpochSecond(s), remoteS => InstantFromLong(remoteS))

    override def evalWithSchema: Either[Remote[Instant], SchemaAndValue[Instant]] =
      unaryEval(seconds)(s => Instant.ofEpochSecond(s), remoteS => InstantFromLong(remoteS))
        .map(SchemaAndValue(Schema[Instant], _))
  }

  final case class InstantToLong[A](instant: Remote[Instant]) extends Remote[Long] {
    override def eval: Either[Remote[Long], Long] =
      unaryEval(instant)(_.toEpochMilli, remoteS => InstantToLong(remoteS))

    override def evalWithSchema: Either[Remote[Long], SchemaAndValue[Long]] =
      unaryEval(instant)(_.toEpochMilli, remoteS => InstantToLong(remoteS)).map(SchemaAndValue(Schema[Long], _))
  }

  final case class DurationToLong[A](duration: Remote[Duration], temporalUnit: Remote[TemporalUnit])
      extends Remote[Long] {
    override def eval: Either[Remote[Long], Long] = binaryEval(duration, temporalUnit)(
      (d, tUnit) => d.get(tUnit),
      (remoteDuration, remoteUnit) => DurationToLong(remoteDuration, remoteUnit)
    )

    override def evalWithSchema: Either[Remote[Long], SchemaAndValue[Long]] = binaryEval(duration, temporalUnit)(
      (d, tUnit) => d.get(tUnit),
      (remoteDuration, remoteUnit) => DurationToLong(remoteDuration, remoteUnit)
    ).map(SchemaAndValue(Schema[Long], _))
  }

  final case class LongToDuration(seconds: Remote[Long]) extends Remote[Duration] {
    override def eval: Either[Remote[Duration], Duration] =
      unaryEval(seconds)(Duration.ofSeconds, remoteS => LongToDuration(remoteS))

    override def evalWithSchema: Either[Remote[Duration], SchemaAndValue[Duration]] =
      unaryEval(seconds)(Duration.ofSeconds, remoteS => LongToDuration(remoteS))
        .map(SchemaAndValue(Schema[Duration], _))
  }

  final case class Iterate[A](
    initial: Remote[A],
    iterate: Remote[A] => Remote[A],
    predicate: Remote[A] => Remote[Boolean]
  ) extends Remote[A] {

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] = {
      def loop(current: Remote[A]): Either[Remote[A], SchemaAndValue[A]] =
        predicate(current).evalWithSchema match {
          case Left(_)      => Left(self)
          case Right(value) => if (value.value) loop(iterate(current)) else current.evalWithSchema
        }
      loop(initial)
    }

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

    override def evalWithSchema: Either[Remote[A], SchemaAndValue[A]] = Left(self)
  }

  final case class Some0[A](value: Remote[A]) extends Remote[Option[A]] {
    override def eval: Either[Remote[Option[A]], Option[A]]           = unaryEval(value)(a => Some(a), remoteA => Some0(remoteA))
    implicit def toSchemaOption(schema: Schema[A]): Schema[Option[A]] = ???

    override def evalWithSchema: Either[Remote[Option[A]], SchemaAndValue[Option[A]]] =
      value.evalWithSchema match {
        case Left(_)                              => Left(self)
        case Right(SchemaAndValue(schema, value)) =>
          val schemaA = schema.asInstanceOf[Schema[A]]
          val a       = value.asInstanceOf[A]
          Right(SchemaAndValue(toSchemaOption(schemaA), Some(a)))
        case Right(_)                             => throw new IllegalStateException("Every remote Some0 must be constructed using Remote[Option].")
      }
  }

  final case class FoldOption[A, B](option: Remote[Option[A]], none: Remote[B], f: Remote[A] => Remote[B])
      extends Remote[B] {
    override def eval: Either[Remote[B], B] = option.eval match {
      case Left(_)   => Left(self)
      case Right(op) => op.fold(none.eval)(v => f(Literal(v, Schema.fail[A]("No schema for B"))).eval)
    }

    override def evalWithSchema: Either[Remote[B], SchemaAndValue[B]] = option.evalWithSchema match {
      case Left(_)                              => Left(self)
      case Right(SchemaAndValue(schema, value)) =>
        val opSchema = schema.asInstanceOf[Schema.Optional[A]]
        val opValue  = value.asInstanceOf[Option[A]]
        opValue.fold(none.evalWithSchema)(v => f(Literal(v, opSchema.codec)).evalWithSchema)
      case _                                    => throw new IllegalStateException("Every remote FoldOption must be constructed using Remote[Option].")
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

  private[zio] def unaryEvalWithSchema[A, B](
    remote: Remote[A]
  )(f: A => B, g: Remote[A] => Remote[B], schema: Schema[B]): Either[Remote[B], SchemaAndValue[B]] =
    remote.eval.fold(remote => Left(g(remote)), a => Right(SchemaAndValue(schema, f(a))))

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

  private[zio] def binaryEvalWithSchema[A, B, C, D](
    left: Remote[A],
    right: Remote[B]
  )(f: (A, B) => D, g: (Remote[A], Remote[B]) => Remote[C], schema: Schema[D]): Either[Remote[C], SchemaAndValue[D]] = {
    val leftEither  = left.evalWithSchema
    val rightEither = right.evalWithSchema
    (for {
      l <- leftEither
      r <- rightEither
    } yield f(l.value, r.value)) match {
      case Left(_)  =>
        Left(g(left, right))
      case Right(v) => Right(SchemaAndValue(schema, v))
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

        case (
              FoldEither(either1, left1, right1),
              FoldEither(either2, left2, right2)
            ) =>
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
