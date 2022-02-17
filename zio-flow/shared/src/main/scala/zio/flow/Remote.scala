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

import zio.flow.remote.Numeric.NumericInt
import zio.flow.remote._
import zio.schema.Diff.SchemaMigration
import zio.schema.Schema
import zio.{Chunk, ZIO}

import java.math.BigDecimal
import java.time.temporal.{ChronoUnit, Temporal, TemporalAmount, TemporalUnit}
import java.time.{Clock, Duration, Instant}
import scala.language.implicitConversions

/**
 * A `Remote[A]` is a blueprint for constructing a value of type `A` on a remote
 * machine. Remote values can always be serialized, because they are mere
 * blueprints, and they do not contain any Scala code.
 */
sealed trait Remote[+A] {

  val schema: Schema[_ <: A]

  def eval: ZIO[RemoteContext, Nothing, Either[Remote[A], A]] = evalWithSchema.map(_.map(_.value))

  def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]]

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

  final def unit: Remote[Unit] = Remote.Ignore()

  override def equals(that: Any): Boolean =
    that match {
      case that: Remote[a] => Remote.checkEquality(self, that)
      case _               => false
    }
}

object Remote {

  /**
   * Constructs accessors that can be used modify remote versions of user
   * defined data types.
   */
  def makeAccessors[A](implicit schema: Schema[A]): schema.Accessors[RemoteLens, RemotePrism, RemoteTraversal] =
    schema.makeAccessors(RemoteAccessorBuilder)

  final case class Literal[A](value: A, schema: Schema[A]) extends Remote[A] {
    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      ZIO.right(SchemaAndValue(schema, value))
  }
  object Literal {
    def apply[A](schemaAndValue: SchemaAndValue[A]): Remote[A] =
      schemaAndValue.toRemote
//
//    def schema[A: Schema]: Schema[Literal[A]] =
//      implicitly[Schema[A]].transform(
//        (a: A) => Literal(a, implicitly[Schema[A]]),
//        (b: Literal[A]) => b.value
//      )
  }

  final case class Ignore() extends Remote[Unit] {
    val schema: Schema[Unit] = Schema[Unit]

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Unit], SchemaAndValue[Unit]]] =
      ZIO.right(SchemaAndValue(Schema[Unit], ()))
  }
  object Ignore {
    val schema: Schema[Unit] = Schema[Unit]
  }

  final case class Variable[A](identifier: RemoteVariableName, schema: Schema[A]) extends Remote[A] {
    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      RemoteContext.getVariable[A](identifier).map {
        case None        => Left(self)
        case Some(value) => Right(SchemaAndValue(schema, value))
      }
  }

  object Variable {
    implicit def schema[A: Schema]: Schema[Variable[A]] =
      Schema.CaseClass1[String, Variable[A]](
        Schema.Field("identifier", Schema.primitive[String]),
        construct = (identifier: String) => Variable(RemoteVariableName(identifier), implicitly[Schema[A]]),
        extractField = (variable: Variable[A]) => RemoteVariableName.unwrap(variable.identifier)
      )
  }

  final case class AddNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = left.schema

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.binaryEval(left, right)(
        (l, r) => SchemaAndValue(numeric.schema, numeric.add(l, r)),
        AddNumeric(_, _, numeric)
      )
  }

  final case class RemoteFunction[A, B] private (
    input: Variable[A],
    result: Remote[B]
  ) extends Remote[B] {
    val schema = result.schema

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
      result.evalWithSchema

    def apply(a: Remote[A]): Remote[B] =
      RemoteApply(this, a)
  }

  object RemoteFunction {
    def apply[A: Schema, B](fn: Remote[A] => Remote[B]): RemoteFunction[A, B] = {
      val input = Variable(RemoteContext.generateFreshVariableName, Schema[A])
      new RemoteFunction(
        input,
        fn(input)
      )
    }
  }

  type ===>[A, B] = RemoteFunction[A, B]

  final case class RemoteApply[A, B](f: RemoteFunction[A, B], a: Remote[A]) extends Remote[B] {
    val schema = f.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
      a.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(value) =>
          RemoteContext.setVariable(f.input.identifier, value.value) *>
            f.result.evalWithSchema.flatMap {
              case Left(value) =>
                ZIO.left(value)
              case Right(result) =>
                Literal(result).evalWithSchema
            }
      }
  }

  final case class DivNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = left.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.binaryEvalWithSchema(left, right)(numeric.divide, DivNumeric(_, _, numeric), numeric.schema)
  }

  final case class MulNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = left.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.binaryEvalWithSchema(left, right)(numeric.multiply, MulNumeric(_, _, numeric), numeric.schema)
  }

  final case class PowNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = left.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.binaryEvalWithSchema(left, right)(numeric.pow, PowNumeric(_, _, numeric), numeric.schema)
  }

  final case class NegationNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.unaryEvalWithSchema(value)(numeric.negate, NegationNumeric(_, numeric), numeric.schema)
  }

  final case class RootNumeric[A](value: Remote[A], n: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.binaryEvalWithSchema(value, n)(numeric.root, RootNumeric(_, _, numeric), numeric.schema)
  }

  final case class LogNumeric[A](value: Remote[A], base: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.binaryEvalWithSchema(value, base)(numeric.log, PowNumeric(_, _, numeric), numeric.schema)
  }

  final case class ModNumeric(left: Remote[Int], right: Remote[Int]) extends Remote[Int] {
    val schema: Schema[Int] = Schema[Int]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Int], SchemaAndValue[Int]]] =
      Remote.binaryEvalWithSchema(left, right)(NumericInt.mod, ModNumeric(_, _), Schema.primitive[Int])
  }

  final case class AbsoluteNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.unaryEvalWithSchema(value)(numeric.abs, AbsoluteNumeric(_, numeric), numeric.schema)
  }

  final case class MinNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = left.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.binaryEvalWithSchema(left, right)(numeric.min, MinNumeric(_, _, numeric), numeric.schema)
  }

  final case class MaxNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = left.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.binaryEvalWithSchema(left, right)(numeric.max, MaxNumeric(_, _, numeric), numeric.schema)
  }

  final case class FloorNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.unaryEvalWithSchema(value)(numeric.floor, FloorNumeric(_, numeric), numeric.schema)
  }

  final case class CeilNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.unaryEvalWithSchema(value)(numeric.ceil, CeilNumeric(_, numeric), numeric.schema)
  }

  final case class RoundNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.unaryEvalWithSchema(value)(numeric.round, RoundNumeric(_, numeric), numeric.schema)
  }

  final case class SinFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.unaryEvalWithSchema(value)(a => fractional.sin(a), SinFractional(_, fractional), fractional.schema)
  }

  final case class SinInverseFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.unaryEvalWithSchema(value)(fractional.inverseSin, SinInverseFractional(_, fractional), fractional.schema)
  }

  final case class TanInverseFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {
    val schema = value.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      Remote.unaryEvalWithSchema(value)(fractional.inverseTan, TanInverseFractional(_, fractional), fractional.schema)
  }

  final case class Either0[A, B](either: Either[(Remote[A], Schema[B]), (Schema[A], Remote[B])])
      extends Remote[Either[A, B]] {
    val schema =
      either match {
        case Left((r, s))  => Schema.either(r.schema, s)
        case Right((s, r)) => Schema.either(s, r.schema)
      }

    override def evalWithSchema
      : ZIO[RemoteContext, Nothing, Either[Remote[Either[A, B]], SchemaAndValue[Either[A, B]]]] =
      either match {
        case Left((remoteA, schemaB)) =>
          remoteA.evalWithSchema.map(
            _.fold(
              remoteA => Left(Either0(Left((remoteA, schemaB)))),
              a => Right(SchemaAndValue(Schema.EitherSchema(a.schema, schemaB), Left(a.value)))
            )
          )
        case Right((schemaA, remoteB)) =>
          remoteB.evalWithSchema.map(
            _.fold(
              remoteB => Left(Either0(Right((schemaA, remoteB)))),
              b => Right(SchemaAndValue(Schema.EitherSchema(schemaA, b.schema), (Right(b.value))))
            )
          )
      }
  }

  final case class FlatMapEither[A, B, C](
    either: Remote[Either[A, B]],
    f: B ===> Either[A, C],
    aSchema: Schema[A],
    cSchema: Schema[C]
  ) extends Remote[Either[A, C]] {
    val schema: Schema[Either[A, C]] = Schema.either(aSchema, cSchema)

    override def evalWithSchema
      : ZIO[RemoteContext, Nothing, Either[Remote[Either[A, C]], SchemaAndValue[Either[A, C]]]] =
      either.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          val schemaEither = schemaAndValue.schema.asInstanceOf[Schema.EitherSchema[A, B]]
          schemaAndValue.value match {
            case Left(a)  => ZIO.right(SchemaAndValue(Schema.EitherSchema(schemaEither.left, cSchema), Left(a)))
            case Right(b) => f(Remote(b)(schemaEither.right)).evalWithSchema
            case _ =>
              throw new IllegalStateException("Every remote FlatMapEither must be constructed using Remote[Either].")
          }
      }
  }

  final case class FoldEither[A, B, C](
    either: Remote[Either[A, B]],
    left: A ===> C,
    right: B ===> C
  ) extends Remote[C] {
    val schema = left.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[C], SchemaAndValue[C]]] =
      either.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          val schemaEither = schemaAndValue.schema.asInstanceOf[Schema.EitherSchema[A, B]]
          schemaAndValue.value match {
            case Left(a)  => left(Literal(a, schemaEither.left)).evalWithSchema
            case Right(b) => right(Literal(b, schemaEither.right)).evalWithSchema
            case _ =>
              throw new IllegalStateException("Every remote FoldEither must be constructed using Remote[Either].")
          }
      }
  }

  final case class SwapEither[A, B](
    either: Remote[Either[A, B]]
  ) extends Remote[Either[B, A]] {
    val schema: Schema[Either[B, A]] =
      Schema.either(
        either.schema.asInstanceOf[Schema.EitherSchema[A, B]].right,
        either.schema.asInstanceOf[Schema.EitherSchema[A, B]].left
      )

    override def evalWithSchema
      : ZIO[RemoteContext, Nothing, Either[Remote[Either[B, A]], SchemaAndValue[Either[B, A]]]] =
      either.evalWithSchema.map {
        case Left(_) => Left(self)
        case Right(schemaAndValue) =>
          val schemaEither = schemaAndValue.schema.asInstanceOf[Schema.EitherSchema[A, B]]
          Right(
            SchemaAndValue(
              Schema.EitherSchema(schemaEither.right, schemaEither.left),
              schemaAndValue.value.asInstanceOf[Either[A, B]].swap
            )
          )
      }
  }

  final case class Try[A](either: Either[(Remote[Throwable], Schema[A]), Remote[A]]) extends Remote[scala.util.Try[A]] {
    self =>
    val schema: Schema[scala.util.Try[A]] = {
      val schemaA = either match {
        case Left((_, schema)) => schema
        case Right(remote)     => remote.schema.asInstanceOf[Schema[A]]
      }
      Schema
        .either(Schema[Throwable], schemaA)
        .transform(
          {
            case Left(error)  => scala.util.Failure(error)
            case Right(value) => scala.util.Success(value)
          },
          {
            case scala.util.Success(value) => Right(value)
            case scala.util.Failure(error) => Left(error)
          }
        )
    }

    def evalWithSchema
      : ZIO[RemoteContext, Nothing, Either[Remote[scala.util.Try[A]], SchemaAndValue[scala.util.Try[A]]]] =
      either match {
        case Left((remoteThrowable, schemaA)) =>
          remoteThrowable.evalWithSchema.map(
            _.fold(
              _ => Left(self),
              throwable => Right(SchemaAndValue(schemaTry(schemaA), scala.util.Failure(throwable.value)))
            )
          )
        case Right(remoteA) =>
          remoteA.evalWithSchema.map(
            _.fold(
              _ => Left(self),
              a => Right(SchemaAndValue(schemaTry(a.schema), scala.util.Success(a.value)))
            )
          )
      }
  }

  final case class Tuple2[A, B](left: Remote[A], right: Remote[B]) extends Remote[(A, B)] {
    val schema =
      Schema.tuple2(left.schema, right.schema)

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[(A, B)], SchemaAndValue[(A, B)]]] =
      for {
        evaluatedLeft  <- left.evalWithSchema
        evaluatedRight <- right.evalWithSchema
        result = (for {
                   l <- evaluatedLeft
                   r <- evaluatedRight
                 } yield (l, r)) match {
                   case Left(_) =>
                     val reducedLeft  = evaluatedLeft.fold(identity, a => Literal(a.value, a.schema))
                     val reducedRight = evaluatedRight.fold(identity, b => Literal(b.value, b.schema))
                     Left(Remote.tuple2((reducedLeft, reducedRight)))
                   case Right((a, b)) =>
                     Right(SchemaAndValue(Schema.Tuple(a.schema, b.schema), (a.value, b.value)))
                 }
      } yield result
  }

  final case class Tuple3[A, B, C](_1: Remote[A], _2: Remote[B], _3: Remote[C]) extends Remote[(A, B, C)] {
    val schema = Schema.tuple3(_1.schema, _2.schema, _3.schema)

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[(A, B, C)], SchemaAndValue[(A, B, C)]]] =
      for {
        first  <- _1.evalWithSchema
        second <- _2.evalWithSchema
        third  <- _3.evalWithSchema
        result =
          (for {
            a <- first
            b <- second
            c <- third
          } yield (a, b, c)) match {
            case Left(_) =>
              val reducedFirst  = first.fold(identity, a => Literal(a.value, a.schema))
              val reducedSecond = second.fold(identity, b => Literal(b.value, b.schema))
              val reducedThird  = third.fold(identity, c => Literal(c.value, c.schema))
              Left(Remote.tuple3((reducedFirst, reducedSecond, reducedThird)))
            case Right((a, b, c)) =>
              Right(SchemaAndValue(Schema.tuple3(a.schema, b.schema, c.schema), (a.value, b.value, c.value)))
          }
      } yield result
  }

  final case class Tuple4[A, B, C, D](_1: Remote[A], _2: Remote[B], _3: Remote[C], _4: Remote[D])
      extends Remote[(A, B, C, D)] {
    val schema = Schema.tuple4(_1.schema, _2.schema, _3.schema, _4.schema)

    override def evalWithSchema
      : ZIO[RemoteContext, Nothing, Either[Remote[(A, B, C, D)], SchemaAndValue[(A, B, C, D)]]] =
      for {
        first  <- _1.evalWithSchema
        second <- _2.evalWithSchema
        third  <- _3.evalWithSchema
        fourth <- _4.evalWithSchema
        result =
          (for {
            a <- first
            b <- second
            c <- third
            d <- fourth
          } yield (a, b, c, d)) match {
            case Left(_) =>
              val reducedFirst  = first.fold(identity, a => Literal(a.value, a.schema))
              val reducedSecond = second.fold(identity, b => Literal(b.value, b.schema))
              val reducedThird  = third.fold(identity, c => Literal(c.value, c.schema))
              val reducedFourth = fourth.fold(identity, d => Literal(d.value, d.schema))
              Left(Remote.tuple4((reducedFirst, reducedSecond, reducedThird, reducedFourth)))
            case Right((a, b, c, d)) =>
              Right(
                SchemaAndValue(
                  Schema.tuple4(a.schema, b.schema, c.schema, d.schema),
                  (a.value, b.value, c.value, d.value)
                )
              )
          }
      } yield result
  }

  final case class First[A, B](tuple: Remote[(A, B)]) extends Remote[A] {
    val schema: Schema[A] = tuple.schema.asInstanceOf[Schema.Tuple[A, B]].left

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._1,
            remoteT => First(remoteT),
            schemaAndValue.schema.asInstanceOf[Schema.Tuple[A, B]].left
          )
      }
  }

  final case class Second[A, B](tuple: Remote[(A, B)]) extends Remote[B] {
    val schema = tuple.schema.asInstanceOf[Schema.Tuple[A, B]].right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._2,
            remoteT => Second(remoteT),
            schemaAndValue.schema.asInstanceOf[Schema.Tuple[A, B]].right
          )
      }
  }

  private def asSchemaOf3[A, B, C](schema: Schema[((A, B), C)]): Schema.Tuple[(A, B), C] =
    schema.asInstanceOf[Schema.Tuple[(A, B), C]]

  private def asSchemaOf4[A, B, C, D](schema: Schema[(((A, B), C), D)]): Schema.Tuple[((A, B), C), D] =
    schema.asInstanceOf[Schema.Tuple[((A, B), C), D]]

  private def asSchemaOf5[A, B, C, D, E](schema: Schema[((((A, B), C), D), E)]): Schema.Tuple[(((A, B), C), D), E] =
    schema.asInstanceOf[Schema.Tuple[(((A, B), C), D), E]]

  private def schemaOf3[A, B, C](schemaAndValue: SchemaAndValue[(A, B, C)]): Schema.Tuple[(A, B), C] =
    asSchemaOf3(schemaAndValue.schema.asInstanceOf[Schema.Transform[((A, B), C), _]].codec)

  private def schemaOf4[A, B, C, D](schemaAndValue: SchemaAndValue[(A, B, C, D)]): Schema.Tuple[((A, B), C), D] =
    asSchemaOf4(schemaAndValue.schema.asInstanceOf[Schema.Transform[(((A, B), C), D), _]].codec)

  private def schemaOf5[A, B, C, D, E](
    schemaAndValue: SchemaAndValue[(A, B, C, D, E)]
  ): Schema.Tuple[(((A, B), C), D), E] =
    asSchemaOf5(schemaAndValue.schema.asInstanceOf[Schema.Transform[((((A, B), C), D), E), _]].codec)

  final case class FirstOf3[A, B, C](tuple: Remote[(A, B, C)]) extends Remote[A] {
    val schema: Schema[A] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .left
        .asInstanceOf[Schema.Tuple[A, B]]
        .left

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._1,
            remoteT => FirstOf3(remoteT),
            schemaOf3(schemaAndValue).left
              .asInstanceOf[Schema.Tuple[A, B]]
              .left
          )
      }
  }

  final case class SecondOf3[A, B, C](tuple: Remote[(A, B, C)]) extends Remote[B] {
    val schema: Schema[B] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .left
        .asInstanceOf[Schema.Tuple[A, B]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._2,
            remoteT => SecondOf3(remoteT),
            schemaOf3(schemaAndValue).left
              .asInstanceOf[Schema.Tuple[A, B]]
              .right
          )
      }
  }

  final case class ThirdOf3[A, B, C](tuple: Remote[(A, B, C)]) extends Remote[C] {
    val schema: Schema[C] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[C], SchemaAndValue[C]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._3,
            remoteT => ThirdOf3(remoteT),
            schemaOf3(schemaAndValue).right
          )
      }
  }

  final case class FirstOf4[A, B, C, D](tuple: Remote[(A, B, C, D)]) extends Remote[A] {
    val schema: Schema[A] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[((A, B), C), D]]
        .left
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .left
        .asInstanceOf[Schema.Tuple[A, B]]
        .left

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._1,
            remoteT => FirstOf4(remoteT), {
              println(schemaAndValue.schema)
              schemaOf4(schemaAndValue).left
                .asInstanceOf[Schema.Tuple[(A, B), C]]
                .left
                .asInstanceOf[Schema.Tuple[A, B]]
                .left
            }
          )
      }
  }

  final case class SecondOf4[A, B, C, D](tuple: Remote[(A, B, C, D)]) extends Remote[B] {
    val schema: Schema[B] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[((A, B), C), D]]
        .left
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .left
        .asInstanceOf[Schema.Tuple[A, B]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._2,
            remoteT => SecondOf4(remoteT),
            schemaOf4(schemaAndValue).left
              .asInstanceOf[Schema.Tuple[(A, B), C]]
              .left
              .asInstanceOf[Schema.Tuple[A, B]]
              .right
          )
      }
  }

  final case class ThirdOf4[A, B, C, D](tuple: Remote[(A, B, C, D)]) extends Remote[C] {
    val schema: Schema[C] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[((A, B), C), D]]
        .left
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[C], SchemaAndValue[C]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._3,
            remoteT => ThirdOf4(remoteT),
            schemaOf4(schemaAndValue).left
              .asInstanceOf[Schema.Tuple[(A, B), C]]
              .right
          )
      }
  }

  final case class FourthOf4[A, B, C, D](tuple: Remote[(A, B, C, D)]) extends Remote[D] {
    val schema: Schema[D] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[((A, B), C), D]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[D], SchemaAndValue[D]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._4,
            remoteT => FourthOf4(remoteT),
            schemaOf4(schemaAndValue).right
          )
      }
  }

  final case class FirstOf5[A, B, C, D, E](tuple: Remote[(A, B, C, D, E)]) extends Remote[A] {
    val schema: Schema[A] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[(((A, B), C), D), E]]
        .left
        .asInstanceOf[Schema.Tuple[((A, B), C), D]]
        .left
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .left
        .asInstanceOf[Schema.Tuple[A, B]]
        .left

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._1,
            remoteT => FirstOf5(remoteT),
            schemaOf5(schemaAndValue).left
              .asInstanceOf[Schema.Tuple[((A, B), C), D]]
              .left
              .asInstanceOf[Schema.Tuple[(A, B), C]]
              .left
              .asInstanceOf[Schema.Tuple[A, B]]
              .left
          )
      }
  }

  final case class SecondOf5[A, B, C, D, E](tuple: Remote[(A, B, C, D, E)]) extends Remote[B] {
    val schema: Schema[B] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[(((A, B), C), D), E]]
        .left
        .asInstanceOf[Schema.Tuple[((A, B), C), D]]
        .left
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .left
        .asInstanceOf[Schema.Tuple[A, B]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._2,
            remoteT => SecondOf5(remoteT),
            schemaOf5(schemaAndValue).left
              .asInstanceOf[Schema.Tuple[((A, B), C), D]]
              .left
              .asInstanceOf[Schema.Tuple[(A, B), C]]
              .left
              .asInstanceOf[Schema.Tuple[A, B]]
              .right
          )
      }
  }

  final case class ThirdOf5[A, B, C, D, E](tuple: Remote[(A, B, C, D, E)]) extends Remote[C] {
    val schema: Schema[C] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[(((A, B), C), D), E]]
        .left
        .asInstanceOf[Schema.Tuple[((A, B), C), D]]
        .left
        .asInstanceOf[Schema.Tuple[(A, B), C]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[C], SchemaAndValue[C]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._3,
            remoteT => ThirdOf5(remoteT),
            schemaOf5(schemaAndValue).left
              .asInstanceOf[Schema.Tuple[((A, B), C), D]]
              .left
              .asInstanceOf[Schema.Tuple[(A, B), C]]
              .right
          )
      }
  }

  final case class FourthOf5[A, B, C, D, E](tuple: Remote[(A, B, C, D, E)]) extends Remote[D] {
    val schema: Schema[D] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[(((A, B), C), D), E]]
        .left
        .asInstanceOf[Schema.Tuple[((A, B), C), D]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[D], SchemaAndValue[D]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._4,
            remoteT => FourthOf5(remoteT),
            schemaOf5(schemaAndValue).left
              .asInstanceOf[Schema.Tuple[((A, B), C), D]]
              .right
          )
      }
  }

  final case class FifthOf5[A, B, C, D, E](tuple: Remote[(A, B, C, D, E)]) extends Remote[E] {
    val schema: Schema[E] =
      tuple.schema
        .asInstanceOf[Schema.Tuple[(((A, B), C), D), E]]
        .right

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[E], SchemaAndValue[E]]] =
      tuple.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          unaryEvalWithSchema(tuple)(
            t => t._5,
            remoteT => FifthOf5(remoteT),
            schemaOf5(schemaAndValue).right
          )
      }
  }

  final case class Branch[A](predicate: Remote[Boolean], ifTrue: Remote[A], ifFalse: Remote[A]) extends Remote[A] {
    val schema: Schema[A] = ifTrue.schema.asInstanceOf[Schema[A]] // TODO: capture merged schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      predicate.eval.flatMap {
        case Left(_)      => ZIO.left(self)
        case Right(value) => if (value) ifTrue.evalWithSchema else ifFalse.evalWithSchema
      }
  }

  case class Length(remoteString: Remote[String]) extends Remote[Int] {
    val schema: Schema[Int] = Schema[Int]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Int], SchemaAndValue[Int]]] =
      unaryEvalWithSchema(remoteString)(str => str.length, remoteStr => Length(remoteStr), Schema[Int])
  }

  final case class LessThanEqual[A](left: Remote[A], right: Remote[A]) extends Remote[Boolean] {
    val schema: Schema[Boolean] = Schema[Boolean]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Boolean], SchemaAndValue[Boolean]]] = for {
      lEval <- left.evalWithSchema
      rEval <- right.evalWithSchema
      result = (lEval, rEval) match {
                 case (Right(SchemaAndValue(leftSchemaA, leftA)), Right(SchemaAndValue(_, rightA))) =>
                   Right(
                     SchemaAndValue(
                       Schema[Boolean],
                       leftSchemaA.ordering.asInstanceOf[Ordering[Any]].compare(leftA, rightA) <= 0
                     )
                   )
                 case _ => Left(self)
               }
    } yield result
  }

  final case class Equal[A](left: Remote[A], right: Remote[A]) extends Remote[Boolean] {
    val schema: Schema[Boolean] = Schema[Boolean]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Boolean], SchemaAndValue[Boolean]]] = for {
      lEval <- left.evalWithSchema
      rEval <- right.evalWithSchema
      result = (lEval, rEval) match {
                 //FIXME : fix when zio schema can compare Schemas
                 case (Right(SchemaAndValue(_, leftA)), Right(SchemaAndValue(_, rightA))) =>
                   Right(
                     SchemaAndValue(Schema[Boolean], (leftA == rightA))
                   )
                 case _ => Left(self)
               }
    } yield result
  }

  final case class Not(value: Remote[Boolean]) extends Remote[Boolean] {
    val schema: Schema[Boolean] = Schema[Boolean]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Boolean], SchemaAndValue[Boolean]]] =
      unaryEval[Boolean, Boolean](value)(a => !a, remoteA => Not(remoteA))
        .map(_.map(SchemaAndValue(Schema[Boolean], _)))
  }

  final case class And(left: Remote[Boolean], right: Remote[Boolean]) extends Remote[Boolean] {
    val schema: Schema[Boolean] = Schema[Boolean]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Boolean], SchemaAndValue[Boolean]]] =
      binaryEval(left, right)((l, r) => l && r, (remoteL, remoteR) => And(remoteL, remoteR))
        .map(_.map(SchemaAndValue(Schema[Boolean], _)))
  }

  final case class Fold[A, B](list: Remote[List[A]], initial: Remote[B], body: (B, A) ===> B) extends Remote[B] {
    val schema = body.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
      list.evalWithSchema.flatMap {
        case Left(_)    => ZIO.left(self)
        case Right(lst) =>
          // TODO: can we avoid this?
          val schemaA = lst.schema.asInstanceOf[Schema[List[A]]] match {
            case Schema.Sequence(schemaA, _, _, _) => schemaA.asInstanceOf[Schema[A]]
            case _                                 => Schema.fail[A]("Failure.")
          }
          initial.evalWithSchema.flatMap { initialValue =>
            ZIO.foldLeft[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]], A](
              lst.value
            )(initialValue) {
              case (Left(_), _) => ZIO.left(self)
              case (Right(schemaAndVal), a) =>
                body(Literal((schemaAndVal.value, a), Schema.Tuple(schemaAndVal.schema, schemaA))).evalWithSchema
            }
          }
      }
  }

  final case class Cons[A](list: Remote[List[A]], head: Remote[A]) extends Remote[List[A]] {
    val schema: Schema[List[A]] = list.schema.asInstanceOf[Schema[List[A]]]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[List[A]], SchemaAndValue[List[A]]]] =
      list.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          binaryEvalWithSchema(list, head)(
            (l, h) => h :: l,
            (remoteL, remoteH) => Cons(remoteL, remoteH),
            schemaAndValue.schema.asInstanceOf[Schema[List[A]]]
          )
      }
  }

  final case class UnCons[A](list: Remote[List[A]]) extends Remote[Option[(A, List[A])]] {
    val schema: Schema[Option[(A, List[A])]] = {
      val listSchema = list.schema.asInstanceOf[Schema.Sequence[List[A], A]]
      Schema.option(Schema.tuple2(listSchema.schemaA, listSchema))
    }

    override def evalWithSchema
      : ZIO[RemoteContext, Nothing, Either[Remote[Option[(A, List[A])]], SchemaAndValue[Option[(A, List[A])]]]] = {
      implicit def toOptionSchema[T](schema: Schema[T]): Schema[Option[T]] = Schema.option(schema)

      implicit def toTupleSchema[S, U](schemaS: Schema[S], schemaU: Schema[U]): Schema[(S, U)] =
        Schema.tuple2(schemaS, schemaU)

      list.evalWithSchema.map {
        case Left(remote) => Left(UnCons(remote))
        case Right(rightVal) =>
          Right(rightVal.value.headOption match {
            case Some(v) =>
              SchemaAndValue(
                toOptionSchema(
                  toTupleSchema(
                    (rightVal.schema.asInstanceOf[SchemaList[A]]) match {
                      case Schema.Sequence(schemaA, _, _, _) => schemaA.asInstanceOf[Schema[A]]
                      case _ =>
                        throw new IllegalStateException("Every remote UnCons must be constructed using Remote[List].")
                    },
                    rightVal.schema.asInstanceOf[SchemaList[A]]
                  )
                ),
                Some((v, rightVal.value.tail))
              )
            case None =>
              val schema = rightVal.schema.asInstanceOf[SchemaList[A]]
              SchemaAndValue(
                toOptionSchema(
                  toTupleSchema(
                    schema match {
                      case Schema.Sequence(schemaA, _, _, _) => schemaA.asInstanceOf[Schema[A]]
                      case _ =>
                        throw new IllegalStateException("Every remote UnCons must be constructed using Remote[List].")
                    },
                    rightVal.schema
                  )
                ),
                None
              )
            case _ => throw new IllegalStateException("Every remote UnCons must be constructed using Remote[List].")
          })
      }
    }
  }

  final case class InstantFromLong(seconds: Remote[Long]) extends Remote[Instant] {
    val schema: Schema[Instant] = Schema[Instant]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
      unaryEval(seconds)(s => Instant.ofEpochSecond(s), remoteS => InstantFromLong(remoteS))
        .map(_.map(SchemaAndValue(Schema[Instant], _)))
  }

  final case class InstantFromLongs(seconds: Remote[Long], nanos: Remote[Long]) extends Remote[Instant] {
    val schema: Schema[Instant] = Schema[Instant]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
      binaryEval(seconds, nanos)(
        (l1, l2) => Instant.ofEpochSecond(l1, l2),
        (remoteS, remoteN) => InstantFromLongs(remoteS, remoteN)
      ).map(_.map(SchemaAndValue(Schema[Instant], _)))
  }

  final case class InstantFromMilli(milliSecond: Remote[Long]) extends Remote[Instant] {
    val schema: Schema[Instant] = Schema[Instant]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
      unaryEval(milliSecond)(l => Instant.ofEpochMilli(l), remoteM => InstantFromMilli(remoteM))
        .map(_.map(SchemaAndValue(Schema[Instant], _)))
  }

  final case class InstantFromClock(clock: Remote[Clock]) extends Remote[Instant] {
    val schema: Schema[Instant] = Schema[Instant]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
      unaryEval(clock)(c => Instant.now(c), remoteC => InstantFromClock(remoteC))
        .map(_.map(SchemaAndValue(Schema[Instant], _)))
  }

  final case class InstantFromString(charSeq: Remote[String]) extends Remote[Instant] {
    val schema: Schema[Instant] = Schema[Instant]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
      unaryEval(charSeq)(chars => Instant.parse(chars), remoteC => InstantFromString(remoteC))
        .map(_.map(SchemaAndValue(Schema[Instant], _)))
  }

  final case class InstantToTuple(instant: Remote[Instant]) extends Remote[(Long, Int)] {
    val schema: Schema[(Long, Int)] = Schema[(Long, Int)]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[(Long, Int)], SchemaAndValue[(Long, Int)]]] =
      unaryEval(instant)(
        instant => (instant.getEpochSecond, instant.getNano),
        remoteT => InstantToTuple(remoteT)
      ).map(_.map(SchemaAndValue(Schema[(Long, Int)], _)))
  }

  final case class InstantPlusDuration(instant: Remote[Instant], duration: Remote[Duration]) extends Remote[Instant] {
    val schema: Schema[Instant] = Schema[Instant]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
      binaryEval(instant, duration)(
        (i, d) => i.plusSeconds(d.getSeconds).plusNanos(d.getNano.toLong),
        (remoteI, remoteD) => InstantPlusDuration(remoteI, remoteD)
      ).map(_.map(SchemaAndValue(Schema[Instant], _)))
  }

  final case class InstantMinusDuration(instant: Remote[Instant], duration: Remote[Duration]) extends Remote[Instant] {
    val schema: Schema[Instant] = Schema[Instant]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
      binaryEval(instant, duration)(
        (i, d) => i.minusSeconds(d.getSeconds).minusNanos(d.getNano.toLong),
        (remoteI, remoteD) => InstantMinusDuration(remoteI, remoteD)
      ).map(_.map(SchemaAndValue(Schema[Instant], _)))
  }

  final case class InstantTruncate(instant: Remote[Instant], tempUnit: Remote[TemporalUnit]) extends Remote[Instant] {
    val schema: Schema[Instant] = Schema[Instant]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
      binaryEval(instant, tempUnit)(
        (i, t) => i.truncatedTo(t),
        (remoteI, remoteU) => InstantTruncate(remoteI, remoteU)
      ).map(_.map(SchemaAndValue(Schema[Instant], _)))
  }

  final case class DurationFromTemporalAmount(amount: Remote[TemporalAmount]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      unaryEval(amount)(a => Duration.from(a), remoteAmount => DurationFromTemporalAmount(remoteAmount))
        .map(_.map(SchemaAndValue(Schema[Duration], _)))
  }

  final case class DurationFromString(charSequence: Remote[String]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      unaryEval(charSequence)(str => Duration.parse(str), remoteCS => DurationFromString(remoteCS))
        .map(_.map(SchemaAndValue(Schema[Duration], _)))
  }

  final case class DurationFromTemporals(start: Remote[Temporal], end: Remote[Temporal]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      binaryEval(start, end)(
        (t1, t2) => Duration.between(t1, t2),
        (remoteT1, remoteT2) => DurationFromTemporals(remoteT1, remoteT2)
      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
  }

  final case class DurationFromBigDecimal(seconds: Remote[BigDecimal]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    private val oneBillion = new BigDecimal(1000000000L)

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      unaryEval(seconds)(
        bd => {
          val seconds = bd.longValue()
          val nanos   = bd.subtract(new BigDecimal(seconds)).multiply(oneBillion).intValue()
          Duration.ofSeconds(seconds, nanos.toLong)
        },
        remoteBD => DurationFromBigDecimal(remoteBD)
      ).map(_.map(SchemaAndValue(Schema[Duration], _)))

  }

  final case class DurationFromLong(seconds: Remote[Long]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      unaryEval(seconds)(Duration.ofSeconds, remoteS => DurationFromLong(remoteS))
        .map(_.map(SchemaAndValue(Schema[Duration], _)))
  }

  final case class DurationFromLongs(seconds: Remote[Long], nanos: Remote[Long]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      binaryEval(seconds, nanos)(
        (seconds, nanos) => Duration.ofSeconds(seconds, nanos),
        (remoteS, remoteN) => DurationFromLongs(remoteS, remoteN)
      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
  }

  final case class DurationFromAmount(amount: Remote[Long], temporal: Remote[TemporalUnit]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      binaryEval(amount, temporal)(
        (amount, unit) => Duration.of(amount, unit),
        (remoteAmount, remoteUnit) => DurationFromAmount(remoteAmount, remoteUnit)
      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
  }

  final case class DurationToLongs(duration: Remote[Duration]) extends Remote[(Long, Long)] {
    val schema: Schema[(Long, Long)] = Schema[(Long, Long)]

    override def evalWithSchema
      : ZIO[RemoteContext, Nothing, Either[Remote[(Long, Long)], SchemaAndValue[(Long, Long)]]] =
      unaryEval(duration)(
        d => (d.getSeconds, d.getNano.toLong),
        remoteDuration => DurationToLongs(remoteDuration)
      ).map(_.map(SchemaAndValue(Schema[(Long, Long)], _)))
  }

  final case class DurationToLong[A](duration: Remote[Duration]) extends Remote[Long] {
    val schema: Schema[Long] = Schema[Long]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Long], SchemaAndValue[Long]]] =
      unaryEval(duration)(
        _.getSeconds(),
        remoteDuration => DurationToLong(remoteDuration)
      ).map(_.map(SchemaAndValue(Schema[Long], _)))
  }

  final case class DurationPlusDuration(left: Remote[Duration], right: Remote[Duration]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      binaryEval(left, right)(
        (d1, d2) => d1.plus(d2),
        (remoteD1, remoteD2) => DurationPlusDuration(remoteD1, remoteD2)
      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
  }

  final case class DurationMinusDuration(left: Remote[Duration], right: Remote[Duration]) extends Remote[Duration] {
    val schema: Schema[Duration] = Schema[Duration]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
      binaryEval(left, right)(
        (d1, d2) => d1.minus(d2),
        (remoteD1, remoteD2) => DurationMinusDuration(remoteD1, remoteD2)
      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
  }

  final case class Iterate[A](
    initial: Remote[A],
    iterate: A ===> A,
    predicate: A ===> Boolean
  ) extends Remote[A] {
    val schema = iterate.schema

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] = {
      def loop(current: Remote[A]): ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
        predicate(current).evalWithSchema.flatMap {
          case Left(_)      => ZIO.left(self)
          case Right(value) => if (value.value) loop(iterate(current)) else current.evalWithSchema
        }

      loop(initial)
    }
  }

  final case class Lazy[A] private (value: () => Remote[A], schema: Schema[A]) extends Remote[A] {
    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      value().evalWithSchema
  }

  object Lazy {
    def apply[A: Schema](value: () => Remote[A]): Remote[A] = {
      lazy val remote             = value()
      val value2: () => Remote[A] = () => remote
      new Lazy(value2, Schema[A])
    }
  }

  final case class Some0[A](value: Remote[A]) extends Remote[Option[A]] {
    val schema = Schema.option(value.schema)

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Option[A]], SchemaAndValue[Option[A]]]] =
      value.evalWithSchema.map {
        case Left(_) => Left(self)
        case Right(SchemaAndValue(schema, value)) =>
          val schemaA = schema.asInstanceOf[Schema[A]]
          val a       = value.asInstanceOf[A]
          Right(SchemaAndValue(Schema.Optional(schemaA), Some(a)))
        case Right(_) => throw new IllegalStateException("Every remote Some0 must be constructed using Remote[Option].")
      }
  }

  final case class FoldOption[A, B](option: Remote[Option[A]], remoteB: Remote[B], f: A ===> B) extends Remote[B] {
    val schema = f.schema

    def schemaFromOption[T](opSchema: Schema[Option[T]]): Schema[T] =
      opSchema.transform(op => op.getOrElse(().asInstanceOf[T]), (value: T) => Some(value))

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
      option.evalWithSchema.flatMap {
        case Left(_) => ZIO.left(self)
        case Right(schemaAndValue) =>
          val schemaA = schemaFromOption(schemaAndValue.schema.asInstanceOf[Schema[Option[A]]])
          schemaAndValue.value.fold(remoteB.evalWithSchema)(v => f(Literal(v, schemaA)).evalWithSchema)
      }
  }

  final case class ZipOption[A, B](remoteA: Remote[Option[A]], remoteB: Remote[Option[B]])
      extends Remote[Option[(A, B)]] {
    val schema =
      Schema.option(
        Schema.tuple2(
          remoteA.schema.asInstanceOf[Schema.Optional[A]].codec,
          remoteB.schema.asInstanceOf[Schema.Optional[B]].codec
        )
      )

    override def evalWithSchema
      : ZIO[RemoteContext, Nothing, Either[Remote[Option[(A, B)]], SchemaAndValue[Option[(A, B)]]]] =
      for {
        lEval <- remoteA.evalWithSchema
        rEval <- remoteB.evalWithSchema
        result = (lEval, rEval) match {
                   case (Right(SchemaAndValue(schemaA, leftA)), Right(SchemaAndValue(schemaB, rightA))) =>
                     val leftVal: Option[A]     = leftA.asInstanceOf[Option[A]]
                     val rightVal: Option[B]    = rightA.asInstanceOf[Option[B]]
                     val schemaAInst: Schema[A] = schemaA.asInstanceOf[Schema[A]]
                     val schemaBInst: Schema[B] = schemaB.asInstanceOf[Schema[B]]

                     val value = if (leftVal.isEmpty || rightVal.isEmpty) None else Some((leftVal.get, rightVal.get))
                     Right(
                       SchemaAndValue(Schema.Optional(Schema.Tuple(schemaAInst, schemaBInst)), value)
                     )
                   case _ => Left(Remote(None))
                 }
      } yield result
  }

  final case class ContainsOption[A](left: Remote[Option[A]], right: A) extends Remote[Boolean] {
    val schema: Schema[Boolean] = Schema[Boolean]

    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Boolean], SchemaAndValue[Boolean]]] =
      left.evalWithSchema.map {
        case (Right(SchemaAndValue(schemaA, leftA))) =>
          val leftVal: Option[A]     = leftA.asInstanceOf[Option[A]]
          val schemaAInst: Schema[A] = schemaA.asInstanceOf[Schema[A]]
          val value: Literal[A]      = Literal(right, schemaAInst)
          Right(
            SchemaAndValue(Schema[Boolean], leftVal.contains(value.value))
          )
        case _ => Left(Remote(false))
      }
  }

  final case class LensGet[S, A](whole: Remote[S], lens: RemoteLens[S, A]) extends Remote[A] {
    val schema: Schema[A] = lens.schemaPiece

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
      whole.evalWithSchema.map {
        case Right(SchemaAndValue(_, whole)) => Right(SchemaAndValue(lens.schemaPiece, lens.unsafeGet(whole)))
        case _                               => Left(self)
      }
  }

  final case class LensSet[S, A](whole: Remote[S], piece: Remote[A], lens: RemoteLens[S, A]) extends Remote[S] {
    val schema: Schema[S] = lens.schemaWhole

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
      whole.evalWithSchema.flatMap {
        case Right(SchemaAndValue(_, whole)) =>
          piece.evalWithSchema.flatMap {
            case Right(SchemaAndValue(_, piece)) =>
              val newValue = lens.unsafeSet(piece)(whole)
              ZIO.right(SchemaAndValue(lens.schemaWhole, newValue))
            case _ => ZIO.left(self)
          }
        case _ => ZIO.left(self)
      }
  }

  final case class PrismGet[S, A](whole: Remote[S], prism: RemotePrism[S, A]) extends Remote[Option[A]] {
    val schema: Schema[Option[A]] = Schema.option(prism.schemaPiece)

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Option[A]], SchemaAndValue[Option[A]]]] =
      whole.evalWithSchema.map {
        case Right(SchemaAndValue(_, whole)) =>
          Right(SchemaAndValue(Schema.option(prism.schemaPiece), prism.unsafeGet(whole)))
        case _ => Left(self)
      }
  }

  final case class PrismSet[S, A](piece: Remote[A], prism: RemotePrism[S, A]) extends Remote[S] {
    val schema: Schema[S] = prism.schemaWhole

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
      piece.evalWithSchema.map {
        case Right(SchemaAndValue(_, piece)) => Right(SchemaAndValue(prism.schemaWhole, prism.unsafeSet(piece)))
        case _                               => Left(self)
      }
  }

  final case class TraversalGet[S, A](whole: Remote[S], traversal: RemoteTraversal[S, A]) extends Remote[Chunk[A]] {
    val schema: Schema[Chunk[A]] = Schema.chunk(traversal.schemaPiece)

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Chunk[A]], SchemaAndValue[Chunk[A]]]] =
      whole.evalWithSchema.map {
        case Right(SchemaAndValue(_, whole)) =>
          Right(SchemaAndValue(Schema.chunk(traversal.schemaPiece), traversal.unsafeGet(whole)))
        case _ => Left(self)
      }
  }

  final case class TraversalSet[S, A](whole: Remote[S], piece: Remote[Chunk[A]], traversal: RemoteTraversal[S, A])
      extends Remote[S] {
    val schema: Schema[S] = traversal.schemaWhole

    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
      whole.evalWithSchema.flatMap {
        case Right(SchemaAndValue(_, whole)) =>
          piece.evalWithSchema.flatMap {
            case Right(SchemaAndValue(_, piece)) =>
              val newValue = traversal.unsafeSet(whole)(piece)
              ZIO.right(SchemaAndValue(traversal.schemaWhole, newValue))
            case _ => ZIO.left(self)
          }
        case _ => ZIO.left(self)
      }
  }

  private[zio] def unaryEval[A, B](
    remote: Remote[A]
  )(f: A => B, g: Remote[A] => Remote[B]): ZIO[RemoteContext, Nothing, Either[Remote[B], B]] =
    remote.eval.map(_.fold(remote => Left(g(remote)), a => Right(f(a))))

  private[zio] def unaryEvalWithSchema[A, B](
    remote: Remote[A]
  )(
    f: A => B,
    g: Remote[A] => Remote[B],
    schema: Schema[B]
  ): ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
    remote.eval.map(_.fold(remote => Left(g(remote)), a => Right(SchemaAndValue(schema, f(a)))))

  private[zio] def binaryEval[A, B, C, D](
    left: Remote[A],
    right: Remote[B]
  )(f: (A, B) => D, g: (Remote[A], Remote[B]) => Remote[C]): ZIO[RemoteContext, Nothing, Either[Remote[C], D]] =
    for {
      leftEither  <- left.eval
      rightEither <- right.eval
      result = (for {
                 l <- leftEither
                 r <- rightEither
               } yield f(l, r)) match {
                 case Left(_) =>
                   Left(g(left, right))
                 case Right(v) => Right(v)
               }
    } yield result

  private[zio] def binaryEvalWithSchema[A, B, C, D](
    left: Remote[A],
    right: Remote[B]
  )(
    f: (A, B) => D,
    g: (Remote[A], Remote[B]) => Remote[C],
    schema: Schema[D]
  ): ZIO[RemoteContext, Nothing, Either[Remote[C], SchemaAndValue[D]]] = for {
    leftEither  <- left.evalWithSchema
    rightEither <- right.evalWithSchema
    result = (for {
               l <- leftEither
               r <- rightEither
             } yield f(l.value, r.value)) match {
               case Left(_) =>
                 Left(g(left, right))
               case Right(v) => Right(SchemaAndValue(schema, v))
             }
  } yield result

  implicit def apply[A: Schema](value: A): Remote[A] =
    Literal(value, Schema[A])

  def checkEquality[A](self: Remote[A], that: Remote[Any]): Boolean = {
    def loop[A](self: Remote[A], that: Remote[Any]): Boolean =
      (self, that) match {
        case (Literal(value1, schema1), Literal(value2, schema2)) =>
          // TODO: Ensure ZIO Schema supports `==` and `hashCode`.
          (value1 == value2) && (schema1 == schema2)

        case (Ignore(), Ignore()) =>
          true

        case (Variable(identifier1, schema1), Variable(identifier2, schema2)) =>
          (identifier1 == identifier2) && (schema1 == schema2)

        case (AddNumeric(left1, right1, numeric1), AddNumeric(left2, right2, numeric2)) =>
          loop(left1, left2) &&
            loop(right1, right2) &&
            (numeric1 == numeric2)

        case (DivNumeric(left1, right1, numeric1), DivNumeric(left2, right2, numeric2)) =>
          loop(left1, left2) &&
            loop(right1, right2) &&
            (numeric1 == numeric2)

        case (MulNumeric(left1, right1, numeric1), MulNumeric(left2, right2, numeric2)) =>
          loop(left1, left2) &&
            loop(right1, right2) &&
            (numeric1 == numeric2)

        case (PowNumeric(left1, right1, numeric1), PowNumeric(left2, right2, numeric2)) =>
          loop(left1, left2) &&
            loop(right1, right2) &&
            (numeric1 == numeric2)

        case (NegationNumeric(value1, numeric1), NegationNumeric(value2, numeric2)) =>
          loop(value1, value2) && (numeric1 == numeric2)

        case (l: Either0[l1, l2], Either0(either2)) =>
          (l.either, either2) match {
            case (Left((l, _)), Left((r, _)))   => loop(l, r)
            case (Right((_, l)), Right((_, r))) => loop(l, r)
            case _                              => false
          }

        case (
              FoldEither(either1, left1, right1),
              FoldEither(either2, left2, right2)
            ) =>
          loop(either1, either2) &&
            loop(left1, left2) &&
            loop(right1, right2)

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

        case (FirstOf3(tuple1), FirstOf3(tuple2))   => loop(tuple1, tuple2)
        case (SecondOf3(tuple1), SecondOf3(tuple2)) => loop(tuple1, tuple2)
        case (ThirdOf3(tuple1), ThirdOf3(tuple2))   => loop(tuple1, tuple2)

        case (FirstOf4(tuple1), FirstOf4(tuple2))   => loop(tuple1, tuple2)
        case (SecondOf4(tuple1), SecondOf4(tuple2)) => loop(tuple1, tuple2)
        case (ThirdOf4(tuple1), ThirdOf4(tuple2))   => loop(tuple1, tuple2)
        case (FourthOf4(tuple1), FourthOf4(tuple2)) => loop(tuple1, tuple2)

        case (FirstOf5(tuple1), FirstOf5(tuple2))   => loop(tuple1, tuple2)
        case (SecondOf5(tuple1), SecondOf5(tuple2)) => loop(tuple1, tuple2)
        case (ThirdOf5(tuple1), ThirdOf5(tuple2))   => loop(tuple1, tuple2)
        case (FourthOf5(tuple1), FourthOf5(tuple2)) => loop(tuple1, tuple2)
        case (FifthOf5(tuple1), FifthOf5(tuple2))   => loop(tuple1, tuple2)

        case (Branch(predicate1, ifTrue1, ifFalse1), Branch(predicate2, ifTrue2, ifFalse2)) =>
          loop(predicate1, predicate2) &&
            loop(ifTrue1, ifTrue2) &&
            loop(ifFalse1, ifFalse2)

        case (l: LessThanEqual[l], LessThanEqual(left2, right2)) =>
          // TODO: Support `==` and `hashCode` for `Sortable`.
          loop(l.left, left2) &&
            loop(l.right, right2)

        case (l: Not, Not(value2)) =>
          loop(l.value, value2)

        case (l: And, And(left2, right2)) =>
          loop(l.left, left2) && loop(l.right, right2)

        case (Fold(list1, initial1, body1), Fold(list2, initial2, body2)) =>
          // TODO: Need Schema[(B, A)]
          // Fold can capture Schema[A] (???), and we already have Schema[B]
          // We can use these to make Schema[(B, A)]
          loop(list1, list2) &&
            loop(initial1, initial2) &&
            loop(body1, body2)

        case (Iterate(initial1, iterate1, predicate1), Iterate(initial2, iterate2, predicate2)) =>
          loop(initial1, initial2) &&
            loop(iterate1, iterate2) &&
            loop(predicate1, predicate2)

        case (Lazy(value1, _), Lazy(value2, _)) =>
          // TODO: Handle loops in the graph appropriately
          loop(value1(), value2())

        case _ => false
      }

    loop(self, that)
  }

  def sequenceEither[A, B](
    either: Either[Remote[A], Remote[B]]
  )(implicit aSchema: Schema[A], bSchema: Schema[B]): Remote[Either[A, B]] =
    Either0(either match {
      case Left(l)  => Left((l, bSchema))
      case Right(r) => Right((aSchema, r))
    })

  def ofEpochSecond(second: Remote[Long]): Remote[Instant] = Remote.InstantFromLong(second)

  def ofEpochSecond(second: Remote[Long], nanos: Remote[Long]): Remote[Instant] = Remote.InstantFromLongs(second, nanos)

  def ofEpochMilli(milliSecond: Remote[Long]): Remote[Instant] = Remote.InstantFromMilli(milliSecond)

  def ofSeconds(seconds: Remote[Long]): Remote[Duration] = Remote.DurationFromLong(seconds)

  def ofSeconds(seconds: Remote[Long], nanos: Remote[Long]): Remote[Duration] = Remote.DurationFromLongs(seconds, nanos)

  def ofMinutes(minutes: Remote[Long]): Remote[Duration] = Remote.ofSeconds(minutes * Remote(60L))

  def ofHours(hours: Remote[Long]): Remote[Duration] = Remote.ofMinutes(hours * Remote(60L))

  def ofDays(days: Remote[Long]): Remote[Duration] = Remote.ofHours(days * Remote(24L))

  def ofMillis(milliseconds: Remote[Long]): Remote[Duration] =
    Remote.DurationFromAmount(milliseconds, Remote(ChronoUnit.MILLIS))

  def ofNanos(nanoseconds: Remote[Long]): Remote[Duration] =
    Remote.DurationFromAmount(nanoseconds, Remote(ChronoUnit.NANOS))

  implicit def tuple2[A, B](t: (Remote[A], Remote[B])): Remote[(A, B)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[A, B, C](t: (Remote[A], Remote[B], Remote[C])): Remote[(A, B, C)] =
    Tuple3(t._1, t._2, t._3)

  implicit def tuple4[A, B, C, D](t: (Remote[A], Remote[B], Remote[C], Remote[D])): Remote[(A, B, C, D)] =
    Tuple4(t._1, t._2, t._3, t._4)

  def suspend[A: Schema](remote: Remote[A]): Remote[A] = Lazy(() => remote)

  implicit def toFlow[A](remote: Remote[A]): ZFlow[Any, Nothing, A] = remote.toFlow

  val unit: Remote[Unit] = Remote(())

  implicit def schemaRemote[A]: Schema[Remote[A]] = Schema.fail("TODO")

}
