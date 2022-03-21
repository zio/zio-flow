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
import zio.schema.CaseSet.:+:
import zio.schema.CaseSet.:+:
import zio.schema.{CaseSet, DynamicValue, Schema, StandardType}
import zio.schema.ast.SchemaAst
import zio.{Chunk, ZIO, schema}

import java.math.BigDecimal
import java.time.temporal.{ChronoUnit, Temporal, TemporalAmount, TemporalUnit}
import java.time.{Clock, Duration, Instant}
import scala.language.implicitConversions

/**
 * A `Remote[A]` is a blueprint for constructing a value of type `A` on a remote
 * machine. Remote values can always be serialized, because they are mere
 * blueprints, and they do not contain any Scala code.
 */
sealed trait Remote[+A] { self =>

  def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]]
  def eval[A1 >: A](implicit schema: Schema[A1]): ZIO[RemoteContext, String, A1] =
    evalDynamic.flatMap(dyn => ZIO.fromEither(dyn.value.toTypedValue(schema)))

//  final def iterate[A1 >: A: Schema](
//    step: Remote[A1] => Remote[A1]
//  )(predicate: Remote[A1] => Remote[Boolean]): Remote[A1] =
//    predicate(self).ifThenElse(
//      step(self).iterate(step)(predicate),
//      self
//    )

//  final def toFlow: ZFlow[Any, Nothing, A] = ZFlow(self)

  final def widen[B](implicit ev: A <:< B): Remote[B] = {
    val _ = ev

    self.asInstanceOf[Remote[B]]
  }

  final def unit: Remote[Unit] = Remote.Ignore()
}

object Remote {
//
//  /**
//   * Constructs accessors that can be used modify remote versions of user
//   * defined data types.
//   */
//  def makeAccessors[A](implicit
//    schema: SchemaOrNothing.Aux[A]
//  ): schema.schema.Accessors[RemoteLens, RemotePrism, RemoteTraversal] =
//    schema.schema.makeAccessors(RemoteAccessorBuilder)

  final case class Literal[A](value: DynamicValue, schema: Schema[A]) extends Remote[A] {

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      ZIO.succeed(SchemaAndValue(schema, value))

    override def eval[A1 >: A](implicit schema: Schema[A1]): ZIO[RemoteContext, String, A1] =
      ZIO.fromEither(value.toTypedValue(schema))

    override def equals(that: Any): Boolean =
      that match {
        case Literal(otherValue, otherSchema) =>
          value == otherValue && Schema.structureEquality.equal(schema, otherSchema)
        case _ => false
      }
  }
  object Literal {
    def apply[A](schemaAndValue: SchemaAndValue[A]): Remote[A] =
      schemaAndValue.toRemote

    def schema[A]: Schema[Literal[A]] =
      Schema
        .semiDynamic[A]()
        .transformOrFail(
          { case (value, schema) => Right(Literal(DynamicValue.fromSchemaAndValue(schema, value), schema)) },
          (lit: Literal[A]) => lit.value.toTypedValue(lit.schema).map((_, lit.schema))
        )

    def schemaCase[A]: Schema.Case[Literal[A], Remote[A]] =
      Schema.Case("Literal", schema[A], _.asInstanceOf[Literal[A]])
  }

  final case class Ignore() extends Remote[Unit] {
    val schema: SchemaOrNothing.Aux[Unit] = SchemaOrNothing.fromSchema[Unit]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Unit]] =
      ZIO.succeed(SchemaAndValue(Schema.primitive[Unit], DynamicValue.Primitive((), StandardType.UnitType)))
  }
  object Ignore {
    val schema: Schema[Ignore] = Schema[Unit].transform(_ => Ignore(), _ => ())

    def schemaCase[A]: Schema.Case[Ignore, Remote[A]] =
      Schema.Case("Ignore", schema, _.asInstanceOf[Ignore])
  }

  final case class Variable[A](identifier: RemoteVariableName, schema: Schema[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      RemoteContext.getVariable(identifier).flatMap {
        case None        => ZIO.fail(s"Could not find identifier $identifier")
        case Some(value) => ZIO.succeed(SchemaAndValue(schema, value))
      }

    override def equals(that: Any): Boolean =
      that match {
        case Variable(otherIdentifier, otherSchema) =>
          otherIdentifier == identifier && Schema.structureEquality.equal(schema, otherSchema)
        case _ => false
      }
  }

  object Variable {
    implicit def schema[A]: Schema[Variable[A]] =
      Schema.CaseClass2[String, SchemaAst, Variable[A]](
        Schema.Field("identifier", Schema.primitive[String]),
        Schema.Field("schema", SchemaAst.schema),
        construct = (identifier: String, s: SchemaAst) =>
          Variable(RemoteVariableName(identifier), s.toSchema.asInstanceOf[Schema[A]]),
        extractField1 = (variable: Variable[A]) => RemoteVariableName.unwrap(variable.identifier),
        extractField2 = (variable: Variable[A]) => SchemaAst.fromSchema(variable.schema)
      )

    def schemaCase[A]: Schema.Case[Variable[A], Remote[A]] =
      Schema.Case("Variable", schema, _.asInstanceOf[Variable[A]])
  }

  final case class EvaluatedRemoteFunction[A, B] private[flow] (
    input: Variable[A],
    result: Remote[B]
  ) extends Remote[B] {

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      result.evalDynamic

    override def eval[A1 >: B](implicit schema: Schema[A1]): ZIO[RemoteContext, String, A1] =
      result.eval[A1]

    def apply(a: Remote[A]): Remote[B] =
      RemoteApply(this, a)
  }

  object EvaluatedRemoteFunction {
    def schema[A, B]: Schema[EvaluatedRemoteFunction[A, B]] =
      Schema.CaseClass2[Variable[A], Remote[B], EvaluatedRemoteFunction[A, B]](
        Schema.Field("variable", Variable.schema[A]),
        Schema.Field("result", Schema.defer(Remote.schema[B])),
        EvaluatedRemoteFunction.apply,
        _.input,
        _.result
      )

    def schemaCase[A, B]: Schema.Case[EvaluatedRemoteFunction[A, B], Remote[B]] =
      Schema.Case("EvaluatedRemoteFunction", schema, _.asInstanceOf[EvaluatedRemoteFunction[A, B]])
  }

  final case class RemoteFunction[A: SchemaOrNothing.Aux, B](fn: Remote[A] => Remote[B]) extends Remote[B] {
    lazy val evaluated: EvaluatedRemoteFunction[A, B] = {
      val input = Variable[A](RemoteContext.generateFreshVariableName, SchemaOrNothing[A].schema)
      EvaluatedRemoteFunction(
        input,
        fn(input)
      )
    }

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      evaluated.evalDynamic

    override def eval[A1 >: B](implicit schema: Schema[A1]): ZIO[RemoteContext, String, A1] =
      evaluated.eval[A1]

    def apply(a: Remote[A]): Remote[B] =
      RemoteApply(evaluated, a)
  }

  type ===>[A, B] = RemoteFunction[A, B]

  final case class RemoteApply[A, B](f: EvaluatedRemoteFunction[A, B], a: Remote[A]) extends Remote[B] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      a.evalDynamic.flatMap { value =>
        RemoteContext.setVariable(f.input.identifier, value.value) *>
          f.evalDynamic
      }
  }

  object RemoteApply {
    def schema[A, B]: Schema[RemoteApply[A, B]] =
      Schema.CaseClass2[EvaluatedRemoteFunction[A, B], Remote[A], RemoteApply[A, B]](
        Schema.Field("f", EvaluatedRemoteFunction.schema[A, B]),
        Schema.Field("a", Schema.defer(Remote.schema[A])),
        RemoteApply.apply,
        _.f,
        _.a
      )

    def schemaCase[A, B]: Schema.Case[RemoteApply[A, B], Remote[B]] =
      Schema.Case[RemoteApply[A, B], Remote[B]](
        "RemoteApply",
        schema[A, B],
        _.asInstanceOf[RemoteApply[A, B]]
      )
  }

  final case class AddNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- left.eval(numeric.schema)
        r <- right.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.add(l, r))

  }

  object AddNumeric {
    def schema[A]: Schema[AddNumeric[A]] =
      Schema.CaseClass3[Remote[A], Remote[A], Numeric[A], AddNumeric[A]](
        Schema.Field("left", Schema.defer(Remote.schema[A])),
        Schema.Field("right", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        AddNumeric.apply,
        _.left,
        _.right,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[AddNumeric[A], Remote[A]] =
      Schema.Case("AddNumeric", schema, _.asInstanceOf[AddNumeric[A]])
  }

  final case class DivNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- left.eval(numeric.schema)
        r <- right.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.divide(l, r))
  }

  object DivNumeric {
    def schema[A]: Schema[DivNumeric[A]] =
      Schema.CaseClass3[Remote[A], Remote[A], Numeric[A], DivNumeric[A]](
        Schema.Field("left", Schema.defer(Remote.schema[A])),
        Schema.Field("right", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        DivNumeric.apply,
        _.left,
        _.right,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[DivNumeric[A], Remote[A]] =
      Schema.Case("DivNumeric", schema, _.asInstanceOf[DivNumeric[A]])
  }

  final case class MulNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- left.eval(numeric.schema)
        r <- right.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.multiply(l, r))
  }

  object MulNumeric {
    def schema[A]: Schema[MulNumeric[A]] =
      Schema.CaseClass3[Remote[A], Remote[A], Numeric[A], MulNumeric[A]](
        Schema.Field("left", Schema.defer(Remote.schema[A])),
        Schema.Field("right", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        MulNumeric.apply,
        _.left,
        _.right,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[MulNumeric[A], Remote[A]] =
      Schema.Case("MulNumeric", schema, _.asInstanceOf[MulNumeric[A]])
  }

  final case class PowNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- left.eval(numeric.schema)
        r <- right.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.pow(l, r))
  }

  object PowNumeric {
    def schema[A]: Schema[PowNumeric[A]] =
      Schema.CaseClass3[Remote[A], Remote[A], Numeric[A], PowNumeric[A]](
        Schema.Field("left", Schema.defer(Remote.schema[A])),
        Schema.Field("right", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        PowNumeric.apply,
        _.left,
        _.right,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[PowNumeric[A], Remote[A]] =
      Schema.Case("PowNumeric", schema, _.asInstanceOf[PowNumeric[A]])
  }

  final case class NegationNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        v <- value.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.negate(v))
  }

  object NegationNumeric {
    def schema[A]: Schema[NegationNumeric[A]] =
      Schema.CaseClass2[Remote[A], Numeric[A], NegationNumeric[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        NegationNumeric.apply,
        _.value,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[NegationNumeric[A], Remote[A]] =
      Schema.Case("NegationNumeric", schema, _.asInstanceOf[NegationNumeric[A]])
  }

  final case class RootNumeric[A](value: Remote[A], n: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- value.eval(numeric.schema)
        r <- n.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.root(l, r))
  }

  object RootNumeric {
    def schema[A]: Schema[RootNumeric[A]] =
      Schema.CaseClass3[Remote[A], Remote[A], Numeric[A], RootNumeric[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("n", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        RootNumeric.apply,
        _.value,
        _.n,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[RootNumeric[A], Remote[A]] =
      Schema.Case("RootNumeric", schema, _.asInstanceOf[RootNumeric[A]])
  }

  final case class LogNumeric[A](value: Remote[A], base: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- value.eval(numeric.schema)
        r <- base.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.log(l, r))
  }

  object LogNumeric {
    def schema[A]: Schema[LogNumeric[A]] =
      Schema.CaseClass3[Remote[A], Remote[A], Numeric[A], LogNumeric[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("base", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        LogNumeric.apply,
        _.value,
        _.base,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[LogNumeric[A], Remote[A]] =
      Schema.Case("LogNumeric", schema, _.asInstanceOf[LogNumeric[A]])
  }

  final case class ModNumeric(left: Remote[Int], right: Remote[Int]) extends Remote[Int] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Int]] =
      for {
        l <- left.eval(NumericInt.schema)
        r <- right.eval(NumericInt.schema)
      } yield SchemaAndValue.fromSchemaAndValue(NumericInt.schema, NumericInt.add(l, r))
  }

  object ModNumeric {
    val schema: Schema[ModNumeric] =
      Schema.CaseClass2[Remote[Int], Remote[Int], ModNumeric](
        Schema.Field("left", Schema.defer(Remote.schema[Int])),
        Schema.Field("right", Schema.defer(Remote.schema[Int])),
        ModNumeric.apply,
        _.left,
        _.right
      )

    def schemaCase[A]: Schema.Case[ModNumeric, Remote[A]] =
      Schema.Case("ModNumeric", schema, _.asInstanceOf[ModNumeric])
  }

  final case class AbsoluteNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        v <- value.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.abs(v))
  }

  object AbsoluteNumeric {
    def schema[A]: Schema[AbsoluteNumeric[A]] =
      Schema.CaseClass2[Remote[A], Numeric[A], AbsoluteNumeric[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        AbsoluteNumeric.apply,
        _.value,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[AbsoluteNumeric[A], Remote[A]] =
      Schema.Case("AbsoluteNumeric", schema, _.asInstanceOf[AbsoluteNumeric[A]])
  }

  final case class MinNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- left.eval(numeric.schema)
        r <- right.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.min(l, r))
  }

  object MinNumeric {
    def schema[A]: Schema[MinNumeric[A]] =
      Schema.CaseClass3[Remote[A], Remote[A], Numeric[A], MinNumeric[A]](
        Schema.Field("left", Schema.defer(Remote.schema[A])),
        Schema.Field("right", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        MinNumeric.apply,
        _.left,
        _.right,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[MinNumeric[A], Remote[A]] =
      Schema.Case("MinNumeric", schema, _.asInstanceOf[MinNumeric[A]])
  }

  final case class MaxNumeric[A](left: Remote[A], right: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- left.eval(numeric.schema)
        r <- right.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.max(l, r))
  }

  object MaxNumeric {
    def schema[A]: Schema[MaxNumeric[A]] =
      Schema.CaseClass3[Remote[A], Remote[A], Numeric[A], MaxNumeric[A]](
        Schema.Field("left", Schema.defer(Remote.schema[A])),
        Schema.Field("right", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        MaxNumeric.apply,
        _.left,
        _.right,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[MaxNumeric[A], Remote[A]] =
      Schema.Case("MaxNumeric", schema, _.asInstanceOf[MaxNumeric[A]])
  }

  final case class FloorNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- value.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.floor(l))
  }

  object FloorNumeric {
    def schema[A]: Schema[FloorNumeric[A]] =
      Schema.CaseClass2[Remote[A], Numeric[A], FloorNumeric[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        FloorNumeric.apply,
        _.value,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[FloorNumeric[A], Remote[A]] =
      Schema.Case("FloorNumeric", schema, _.asInstanceOf[FloorNumeric[A]])
  }

  final case class CeilNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- value.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.ceil(l))
  }

  object CeilNumeric {
    def schema[A]: Schema[CeilNumeric[A]] =
      Schema.CaseClass2[Remote[A], Numeric[A], CeilNumeric[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        CeilNumeric.apply,
        _.value,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[CeilNumeric[A], Remote[A]] =
      Schema.Case("CeilNumeric", schema, _.asInstanceOf[CeilNumeric[A]])
  }

  final case class RoundNumeric[A](value: Remote[A], numeric: Numeric[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- value.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.round(l))
  }

  object RoundNumeric {
    def schema[A]: Schema[RoundNumeric[A]] =
      Schema.CaseClass2[Remote[A], Numeric[A], RoundNumeric[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        RoundNumeric.apply,
        _.value,
        _.numeric
      )

    def schemaCase[A]: Schema.Case[RoundNumeric[A], Remote[A]] =
      Schema.Case("RoundNumeric", schema, _.asInstanceOf[RoundNumeric[A]])
  }

  final case class SinFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        v <- value.eval(fractional.schema)
      } yield SchemaAndValue(fractional.schema, DynamicValue.fromSchemaAndValue(fractional.schema, fractional.sin(v)))
  }

  object SinFractional {
    def schema[A]: Schema[SinFractional[A]] =
      Schema.CaseClass2[Remote[A], Fractional[A], SinFractional[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Fractional.schema.asInstanceOf[Schema[Fractional[A]]]),
        SinFractional.apply,
        _.value,
        _.fractional
      )

    def schemaCase[A]: Schema.Case[SinFractional[A], Remote[A]] =
      Schema.Case("SinFractional", schema, _.asInstanceOf[SinFractional[A]])
  }

  final case class SinInverseFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        v <- value.eval(fractional.schema)
      } yield SchemaAndValue(
        fractional.schema,
        DynamicValue.fromSchemaAndValue(fractional.schema, fractional.inverseSin(v))
      )
  }

  object SinInverseFractional {
    def schema[A]: Schema[SinInverseFractional[A]] =
      Schema.CaseClass2[Remote[A], Fractional[A], SinInverseFractional[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Fractional.schema.asInstanceOf[Schema[Fractional[A]]]),
        SinInverseFractional.apply,
        _.value,
        _.fractional
      )

    def schemaCase[A]: Schema.Case[SinInverseFractional[A], Remote[A]] =
      Schema.Case("SinInverseFractional", schema, _.asInstanceOf[SinInverseFractional[A]])
  }

  final case class TanInverseFractional[A](value: Remote[A], fractional: Fractional[A]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        v <- value.eval(fractional.schema)
      } yield SchemaAndValue(
        fractional.schema,
        DynamicValue.fromSchemaAndValue(fractional.schema, fractional.inverseTan(v))
      )
  }

  object TanInverseFractional {
    def schema[A]: Schema[TanInverseFractional[A]] =
      Schema.CaseClass2[Remote[A], Fractional[A], TanInverseFractional[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Fractional.schema.asInstanceOf[Schema[Fractional[A]]]),
        TanInverseFractional.apply,
        _.value,
        _.fractional
      )

    def schemaCase[A]: Schema.Case[TanInverseFractional[A], Remote[A]] =
      Schema.Case("TanInverseFractional", schema, _.asInstanceOf[TanInverseFractional[A]])
  }

  // TODO: better name
  final case class Either0[A, B](
    either: Either[(Remote[A], SchemaOrNothing.Aux[B]), (SchemaOrNothing.Aux[A], Remote[B])]
  ) extends Remote[Either[A, B]] {

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Either[A, B]]] =
      either match {
        case Left((left, rightSchema)) =>
          left.evalDynamic.flatMap { evaluatedLeft =>
            ZIO.fromEither(evaluatedLeft.toTyped).map { leftValue =>
              SchemaAndValue.fromSchemaAndValue[Either[A, B]](
                Schema.either[A, B](
                  evaluatedLeft.schema.asInstanceOf[Schema[A]],
                  rightSchema.schema
                ),
                Left(leftValue)
              )
            }
          }

        case Right((leftSchema, right)) =>
          right.evalDynamic.flatMap { evaluatedRight =>
            ZIO.fromEither(evaluatedRight.toTyped).map { rigthValue =>
              SchemaAndValue.fromSchemaAndValue[Either[A, B]](
                Schema.either[A, B](
                  leftSchema.schema,
                  evaluatedRight.schema.asInstanceOf[Schema[B]]
                ),
                Right(rigthValue)
              )
            }
          }
      }

    override def equals(that: Any): Boolean =
      that match {
        case Either0(otherEither) =>
          (either, otherEither) match {
            case (Left((value, schema)), Left((otherValue, otherSchema))) =>
              value == otherValue && Schema.structureEquality.equal(schema.schema, otherSchema.schema)
            case (Right((schema, value)), Right((otherSchema, otherValue))) =>
              value == otherValue && Schema.structureEquality.equal(schema.schema, otherSchema.schema)
            case _ => false
          }
        case _ => false
      }
  }

  object Either0 {
    private def leftSchema[A, B]: Schema[(Remote[A], SchemaOrNothing.Aux[B])] =
      Schema
        .tuple2(
          Schema.defer(Remote.schema[A]),
          SchemaAst.schema
        )
        .transform(
          { case (v, ast) => (v, SchemaOrNothing.fromSchema(ast.toSchema.asInstanceOf[Schema[B]])) },
          { case (v, s) => (v, s.schema.ast) }
        )

    private def rightSchema[A, B]: Schema[(SchemaOrNothing.Aux[A], Remote[B])] =
      Schema
        .tuple2(
          SchemaAst.schema,
          Schema.defer(Remote.schema[B])
        )
        .transform(
          { case (ast, v) => (SchemaOrNothing.fromSchema(ast.toSchema.asInstanceOf[Schema[A]]), v) },
          { case (s, v) => (s.schema.ast, v) }
        )

    private def eitherSchema[A, B]
      : Schema[Either[(Remote[A], SchemaOrNothing.Aux[B]), (SchemaOrNothing.Aux[A], Remote[B])]] =
      Schema.either(leftSchema[A, B], rightSchema[A, B])

    def schema[A, B]: Schema[Either0[A, B]] =
      eitherSchema[A, B].transform(
        Either0.apply,
        _.either
      )

    def schemaCase[A]: Schema.Case[Either0[Any, Any], Remote[A]] =
      Schema.Case("Either0", schema[Any, Any], _.asInstanceOf[Either0[Any, Any]])
  }

  final case class FlatMapEither[A, B, C](
    either: Remote[Either[A, B]],
    f: EvaluatedRemoteFunction[B, Either[A, C]],
    aSchema: SchemaOrNothing.Aux[A],
    cSchema: SchemaOrNothing.Aux[C]
  ) extends Remote[Either[A, C]] {

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Either[A, C]]] =
      either.eval[Either[A, B]](Schema.either(aSchema.schema, f.input.schema)).flatMap {
        case Left(a) =>
          ZIO.succeed(SchemaAndValue.fromSchemaAndValue(Schema.either(aSchema.schema, cSchema.schema), Left(a)))
        case Right(b) =>
          f(Remote(b)(f.input.schema)).evalDynamic
      }

    override def equals(that: Any): Boolean =
      that match {
        case FlatMapEither(otherEither, otherF, otherASchema, otherCSchema) =>
          either == otherEither &&
            f == otherF &&
            Schema.structureEquality.equal(aSchema.schema, otherASchema.schema) &&
            Schema.structureEquality.equal(cSchema.schema, otherCSchema.schema)
        case _ => false
      }
  }

  object FlatMapEither {
    def schema[A, B, C]: Schema[FlatMapEither[A, B, C]] =
      Schema.CaseClass4[Remote[Either[A, B]], EvaluatedRemoteFunction[
        B,
        Either[A, C]
      ], SchemaAst, SchemaAst, FlatMapEither[A, B, C]](
        Schema.Field("either", Schema.defer(Remote.schema[Either[A, B]])),
        Schema.Field("f", EvaluatedRemoteFunction.schema[B, Either[A, C]]),
        Schema.Field("aSchema", SchemaAst.schema),
        Schema.Field("cSchema", SchemaAst.schema),
        { case (either, ef, aAst, cAst) =>
          FlatMapEither(
            either,
            ef,
            SchemaOrNothing.fromSchema(aAst.toSchema.asInstanceOf[Schema[A]]),
            SchemaOrNothing.fromSchema(cAst.toSchema.asInstanceOf[Schema[C]])
          )
        },
        _.either,
        _.f,
        _.aSchema.schema.ast,
        _.cSchema.schema.ast
      )

    def schemaCase[A]: Schema.Case[FlatMapEither[Any, Any, Any], Remote[A]] =
      Schema.Case("FlatMapEither", schema[Any, Any, Any], _.asInstanceOf[FlatMapEither[Any, Any, Any]])
  }

  final case class FoldEither[A, B, C](
    either: Remote[Either[A, B]],
    left: EvaluatedRemoteFunction[A, C],
    right: EvaluatedRemoteFunction[B, C]
  ) extends Remote[C] {

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[C]] =
      either.eval(Schema.either(left.input.schema, right.input.schema)).flatMap {
        case Left(a) =>
          left(Remote(a)(left.input.schema)).evalDynamic
        case Right(b) =>
          right(Remote(b)(right.input.schema)).evalDynamic
      }
  }

  object FoldEither {
    def schema[A, B, C]: Schema[FoldEither[A, B, C]] =
      Schema.CaseClass3[
        Remote[Either[A, B]],
        EvaluatedRemoteFunction[A, C],
        EvaluatedRemoteFunction[B, C],
        FoldEither[A, B, C]
      ](
        Schema.Field("either", Schema.defer(Remote.schema[Either[A, B]])),
        Schema.Field("left", EvaluatedRemoteFunction.schema[A, C]),
        Schema.Field("right", EvaluatedRemoteFunction.schema[B, C]),
        { case (either, left, right) =>
          FoldEither(
            either,
            left,
            right
          )
        },
        _.either,
        _.left,
        _.right
      )

    def schemaCase[A, B, C]: Schema.Case[FoldEither[A, B, C], Remote[C]] =
      Schema.Case("FoldEither", schema[A, B, C], _.asInstanceOf[FoldEither[A, B, C]])
  }

  final case class SwapEither[A, B](
    either: Remote[Either[A, B]]
  ) extends Remote[Either[B, A]] {

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Either[B, A]]] =
      either.evalDynamic.flatMap { schemaAndValue =>
        val evaluatedEitherSchema = schemaAndValue.schema.asInstanceOf[Schema.EitherSchema[A, B]]
        val swappedEitherSchema   = Schema.either(evaluatedEitherSchema.right, evaluatedEitherSchema.left)
        ZIO.fromEither(schemaAndValue.value.toTypedValue(evaluatedEitherSchema)).map { eitherValue =>
          SchemaAndValue.fromSchemaAndValue(swappedEitherSchema, eitherValue.swap)
        }
      }
  }

  object SwapEither {
    def schema[A, B]: Schema[SwapEither[A, B]] =
      Schema
        .defer(
          Remote
            .schema[Either[A, B]]
            .transform(
              SwapEither(_),
              _.either
            )
        )

    def schemaCase[A]: Schema.Case[SwapEither[Any, Any], Remote[A]] =
      Schema.Case("SwapEither", schema[Any, Any], _.asInstanceOf[SwapEither[Any, Any]])
  }

  final case class Try[A](either: Either[(Remote[Throwable], SchemaOrNothing.Aux[A]), Remote[A]])
      extends Remote[scala.util.Try[A]] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[util.Try[A]]] =
      either match {
        case Left((remoteThrowable, valueSchema)) =>
          val trySchema = schemaTry(valueSchema.schema)
          remoteThrowable.eval(schemaThrowable).map { throwable =>
            SchemaAndValue.fromSchemaAndValue(trySchema, scala.util.Failure(throwable))
          }
        case Right(remoteValue) =>
          remoteValue.evalDynamic.flatMap { schemaAndValue =>
            ZIO.fromEither(schemaAndValue.value.toTypedValue(schemaAndValue.schema)).map { value =>
              val trySchema = schemaTry(schemaAndValue.schema.asInstanceOf[Schema[A]])
              SchemaAndValue.fromSchemaAndValue(trySchema, scala.util.Success(value))
            }
          }
      }

    override def equals(obj: Any): Boolean =
      obj match {
        case Try(otherEither) =>
          (either, otherEither) match {
            case (Left((value, schema)), Left((otherValue, otherSchema))) =>
              value == otherValue && Schema.structureEquality.equal(schema.schema, otherSchema.schema)
            case (Right(value), Right(otherValue)) =>
              value == otherValue
            case _ => false
          }
      }
  }

  object Try {
    private def leftSchema[A]: Schema[(Remote[Throwable], SchemaOrNothing.Aux[A])] =
      Schema
        .tuple2(
          Schema.defer(Remote.schema[Throwable]),
          SchemaAst.schema
        )
        .transform(
          { case (v, ast) => (v, SchemaOrNothing.fromSchema(ast.toSchema.asInstanceOf[Schema[A]])) },
          { case (v, s) => (v, s.schema.ast) }
        )

    private def eitherSchema[A]: Schema[Either[(Remote[Throwable], SchemaOrNothing.Aux[A]), Remote[A]]] =
      Schema.either(leftSchema[A], Remote.schema[A])

    def schema[A]: Schema[Try[A]] =
      Schema.defer(
        eitherSchema[A].transform(
          Try.apply,
          _.either
        )
      )

    def schemaCase[A]: Schema.Case[Try[Any], Remote[A]] =
      Schema.Case("Try", schema[Any], _.asInstanceOf[Try[Any]])
  }

  // TODO: generate the tuple constructor / selector remotes?

  final case class Tuple2[A, B](left: Remote[A], right: Remote[B]) extends Remote[(A, B)] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[(A, B)]] =
      for {
        leftDyn  <- left.evalDynamic
        rightDyn <- right.evalDynamic

        a <- ZIO.fromEither(leftDyn.toTyped)
        b <- ZIO.fromEither(rightDyn.toTyped)

        result = SchemaAndValue.fromSchemaAndValue(
                   Schema.tuple2(leftDyn.schema, rightDyn.schema),
                   (a, b)
                 )
      } yield result
  }

  object Tuple2 {
    def schema[A, B]: Schema[Tuple2[A, B]] =
      Schema.defer(
        Schema
          .tuple2(Remote.schema[A], Remote.schema[B])
          .transform(
            { case (a, b) => Tuple2(a, b) },
            (t: Tuple2[A, B]) => (t.left, t.right)
          )
      )

    def schemaCase[A]: Schema.Case[Tuple2[Any, Any], Remote[A]] =
      Schema.Case("Tuple2", schema[Any, Any], _.asInstanceOf[Tuple2[Any, Any]])

  }

  final case class Tuple3[A, B, C](_1: Remote[A], _2: Remote[B], _3: Remote[C]) extends Remote[(A, B, C)] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[(A, B, C)]] =
      for {
        dyn1 <- _1.evalDynamic
        dyn2 <- _2.evalDynamic
        dyn3 <- _3.evalDynamic

        a <- ZIO.fromEither(dyn1.toTyped)
        b <- ZIO.fromEither(dyn2.toTyped)
        c <- ZIO.fromEither(dyn3.toTyped)

        result = SchemaAndValue.fromSchemaAndValue(
                   Schema.tuple3(dyn1.schema, dyn2.schema, dyn3.schema),
                   (a, b, c)
                 )
      } yield result
  }

  object Tuple3 {
    def schema[A, B, C]: Schema[Tuple3[A, B, C]] =
      Schema.defer(
        Schema
          .tuple3(Remote.schema[A], Remote.schema[B], Remote.schema[C])
          .transform(
            { case (a, b, c) => Tuple3(a, b, c) },
            (t: Tuple3[A, B, C]) => (t._1, t._2, t._3)
          )
      )

    def schemaCase[A]: Schema.Case[Tuple3[Any, Any, Any], Remote[A]] =
      Schema.Case("Tuple3", schema[Any, Any, Any], _.asInstanceOf[Tuple3[Any, Any, Any]])

  }

  final case class Tuple4[A, B, C, D](_1: Remote[A], _2: Remote[B], _3: Remote[C], _4: Remote[D])
      extends Remote[(A, B, C, D)] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[(A, B, C, D)]] =
      for {
        dyn1 <- _1.evalDynamic
        dyn2 <- _2.evalDynamic
        dyn3 <- _3.evalDynamic
        dyn4 <- _4.evalDynamic

        a <- ZIO.fromEither(dyn1.toTyped)
        b <- ZIO.fromEither(dyn2.toTyped)
        c <- ZIO.fromEither(dyn3.toTyped)
        d <- ZIO.fromEither(dyn4.toTyped)

        result = SchemaAndValue.fromSchemaAndValue(
                   Schema.tuple4(dyn1.schema, dyn2.schema, dyn3.schema, dyn4.schema),
                   (a, b, c, d)
                 )
      } yield result
  }

  object Tuple4 {
    def schema[A, B, C, D]: Schema[Tuple4[A, B, C, D]] =
      Schema.defer(
        Schema
          .tuple4(Remote.schema[A], Remote.schema[B], Remote.schema[C], Remote.schema[D])
          .transform(
            { case (a, b, c, d) => Tuple4(a, b, c, d) },
            (t: Tuple4[A, B, C, D]) => (t._1, t._2, t._3, t._4)
          )
      )

    def schemaCase[A]: Schema.Case[Tuple4[Any, Any, Any, Any], Remote[A]] =
      Schema.Case("Tuple4", schema[Any, Any, Any, Any], _.asInstanceOf[Tuple4[Any, Any, Any, Any]])

  }

  final case class First[A, B](tuple: Remote[(A, B)]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        dyn        <- tuple.evalDynamic
        tupleSchema = dyn.schema.asInstanceOf[Schema.Tuple[A, B]]
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(tupleSchema.left, tupleValue._1)
  }

  object First {
    def schema[A, B]: Schema[First[A, B]] =
      Schema.defer(
        Remote
          .schema[(A, B)]
          .transform(
            First.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[First[A, Any], Remote[A]] =
      Schema.Case("First", schema[A, Any], _.asInstanceOf[First[A, Any]])
  }

  final case class Second[A, B](tuple: Remote[(A, B)]) extends Remote[B] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      for {
        dyn        <- tuple.evalDynamic
        tupleSchema = dyn.schema.asInstanceOf[Schema.Tuple[A, B]]
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(tupleSchema.right, tupleValue._2)
  }

  object Second {
    def schema[A, B]: Schema[Second[A, B]] =
      Schema.defer(
        Remote
          .schema[(A, B)]
          .transform(
            Second.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[Second[A, Any], Remote[A]] =
      Schema.Case("Second", schema[A, Any], _.asInstanceOf[Second[A, Any]])
  }

  final case class FirstOf3[A, B, C](tuple: Remote[(A, B, C)]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        dyn <- tuple.evalDynamic
        schema = dyn.schema
                   .asInstanceOf[Schema.Transform[((A, B), C), _, _]]
                   .codec
                   .asInstanceOf[Schema.Tuple[(A, B), C]]
                   .left
                   .asInstanceOf[Schema.Tuple[A, B]]
                   .left
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(schema, tupleValue._1)
  }

  object FirstOf3 {
    def schema[A, B, C]: Schema[FirstOf3[A, B, C]] =
      Schema.defer(
        Remote
          .schema[(A, B, C)]
          .transform(
            FirstOf3.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[FirstOf3[A, Any, Any], Remote[A]] =
      Schema.Case("FirstOf3", schema[A, Any, Any], _.asInstanceOf[FirstOf3[A, Any, Any]])
  }

  final case class SecondOf3[A, B, C](tuple: Remote[(A, B, C)]) extends Remote[B] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      for {
        dyn <- tuple.evalDynamic
        schema = dyn.schema
                   .asInstanceOf[Schema.Transform[((A, B), C), _, _]]
                   .codec
                   .asInstanceOf[Schema.Tuple[(A, B), C]]
                   .left
                   .asInstanceOf[Schema.Tuple[A, B]]
                   .right
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(schema, tupleValue._2)
  }

  object SecondOf3 {
    def schema[A, B, C]: Schema[SecondOf3[A, B, C]] =
      Schema.defer(
        Remote
          .schema[(A, B, C)]
          .transform(
            SecondOf3.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[SecondOf3[Any, A, Any], Remote[A]] =
      Schema.Case("SecondOf3", schema[Any, A, Any], _.asInstanceOf[SecondOf3[Any, A, Any]])
  }

  final case class ThirdOf3[A, B, C](tuple: Remote[(A, B, C)]) extends Remote[C] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[C]] =
      for {
        dyn <- tuple.evalDynamic
        schema = dyn.schema
                   .asInstanceOf[Schema.Transform[((A, B), C), _, _]]
                   .codec
                   .asInstanceOf[Schema.Tuple[(A, B), C]]
                   .right
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(schema, tupleValue._3)
  }

  object ThirdOf3 {
    def schema[A, B, C]: Schema[ThirdOf3[A, B, C]] =
      Schema.defer(
        Remote
          .schema[(A, B, C)]
          .transform(
            ThirdOf3.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[ThirdOf3[Any, Any, A], Remote[A]] =
      Schema.Case("ThirdOf3", schema[Any, Any, A], _.asInstanceOf[ThirdOf3[Any, Any, A]])
  }

  final case class FirstOf4[A, B, C, D](tuple: Remote[(A, B, C, D)]) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        dyn <- tuple.evalDynamic
        schema = dyn.schema
                   .asInstanceOf[Schema.Transform[(((A, B), C), D), _, _]]
                   .codec
                   .asInstanceOf[Schema.Tuple[((A, B), C), D]]
                   .left
                   .asInstanceOf[Schema.Tuple[(A, B), C]]
                   .left
                   .asInstanceOf[Schema.Tuple[A, B]]
                   .left
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(schema, tupleValue._1)
  }

  object FirstOf4 {
    def schema[A, B, C, D]: Schema[FirstOf4[A, B, C, D]] =
      Schema.defer(
        Remote
          .schema[(A, B, C, D)]
          .transform(
            FirstOf4.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[FirstOf4[A, Any, Any, Any], Remote[A]] =
      Schema.Case("FirstOf4", schema[A, Any, Any, Any], _.asInstanceOf[FirstOf4[A, Any, Any, Any]])
  }

  final case class SecondOf4[A, B, C, D](tuple: Remote[(A, B, C, D)]) extends Remote[B] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      for {
        dyn <- tuple.evalDynamic
        schema = dyn.schema
                   .asInstanceOf[Schema.Transform[(((A, B), C), D), _, _]]
                   .codec
                   .asInstanceOf[Schema.Tuple[((A, B), C), D]]
                   .left
                   .asInstanceOf[Schema.Tuple[(A, B), C]]
                   .left
                   .asInstanceOf[Schema.Tuple[A, B]]
                   .right
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(schema, tupleValue._2)
  }

  object SecondOf4 {
    def schema[A, B, C, D]: Schema[SecondOf4[A, B, C, D]] =
      Schema.defer(
        Remote
          .schema[(A, B, C, D)]
          .transform(
            SecondOf4.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[SecondOf4[Any, A, Any, Any], Remote[A]] =
      Schema.Case("SecondOf4", schema[Any, A, Any, Any], _.asInstanceOf[SecondOf4[Any, A, Any, Any]])
  }

  final case class ThirdOf4[A, B, C, D](tuple: Remote[(A, B, C, D)]) extends Remote[C] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[C]] =
      for {
        dyn <- tuple.evalDynamic
        schema = dyn.schema
                   .asInstanceOf[Schema.Transform[(((A, B), C), D), _, _]]
                   .codec
                   .asInstanceOf[Schema.Tuple[((A, B), C), D]]
                   .left
                   .asInstanceOf[Schema.Tuple[(A, B), C]]
                   .right
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(schema, tupleValue._3)
  }

  object ThirdOf4 {
    def schema[A, B, C, D]: Schema[ThirdOf4[A, B, C, D]] =
      Schema.defer(
        Remote
          .schema[(A, B, C, D)]
          .transform(
            ThirdOf4.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[ThirdOf4[Any, Any, A, Any], Remote[A]] =
      Schema.Case("ThirdOf4", schema[Any, Any, A, Any], _.asInstanceOf[ThirdOf4[Any, Any, A, Any]])
  }

  final case class FourthOf4[A, B, C, D](tuple: Remote[(A, B, C, D)]) extends Remote[D] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[D]] =
      for {
        dyn <- tuple.evalDynamic
        schema = dyn.schema
                   .asInstanceOf[Schema.Transform[(((A, B), C), D), _, _]]
                   .codec
                   .asInstanceOf[Schema.Tuple[((A, B), C), D]]
                   .right
        tupleValue <- ZIO.fromEither(dyn.toTyped)
      } yield SchemaAndValue.fromSchemaAndValue(schema, tupleValue._4)
  }

  object FourthOf4 {
    def schema[A, B, C, D]: Schema[FourthOf4[A, B, C, D]] =
      Schema.defer(
        Remote
          .schema[(A, B, C, D)]
          .transform(
            FourthOf4.apply,
            _.tuple
          )
      )

    def schemaCase[A]: Schema.Case[FourthOf4[Any, Any, Any, A], Remote[A]] =
      Schema.Case("FourthOf4", schema[Any, Any, Any, A], _.asInstanceOf[FourthOf4[Any, Any, Any, A]])
  }

  final case class Branch[A](predicate: Remote[Boolean], ifTrue: Remote[A], ifFalse: Remote[A]) extends Remote[A] {

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      predicate.eval.flatMap {
        case false => ifFalse.evalDynamic
        case true  => ifTrue.evalDynamic
      }
  }

  object Branch {
    def schema[A]: Schema[Branch[A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[Boolean], Remote[A], Remote[A], Branch[A]](
          Schema.Field("predicate", Remote.schema[Boolean]),
          Schema.Field("ifTrue", Remote.schema[A]),
          Schema.Field("ifFalse", Remote.schema[A]),
          { case (predicate, ifTrue, ifFalse) => Branch(predicate, ifTrue, ifFalse) },
          _.predicate,
          _.ifTrue,
          _.ifFalse
        )
      )

    def schemaCase[A]: Schema.Case[Branch[A], Remote[A]] =
      Schema.Case("Branch", schema[A], _.asInstanceOf[Branch[A]])
  }

  case class Length(remoteString: Remote[String]) extends Remote[Int] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Int]] =
      remoteString.eval.map { value =>
        SchemaAndValue.fromSchemaAndValue(Schema[Int], value.length)
      }
  }

  object Length {
    val schema: Schema[Length] = Schema.defer(
      Remote
        .schema[String]
        .transform(
          Length.apply,
          _.remoteString
        )
    )

    def schemaCase[A]: Schema.Case[Length, Remote[A]] =
      Schema.Case("Length", schema, _.asInstanceOf[Length])
  }

  final case class LessThanEqual[A](left: Remote[A], right: Remote[A]) extends Remote[Boolean] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      for {
        leftDyn      <- left.evalDynamic
        rightDyn     <- right.evalDynamic
        ordering      = leftDyn.schema.ordering
        leftVal      <- ZIO.fromEither(leftDyn.toTyped)
        rightVal     <- ZIO.fromEither(rightDyn.toTyped)
        compareResult = ordering.compare(leftVal, rightVal.asInstanceOf[leftDyn.Subtype])
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Boolean], compareResult <= 0)
  }

  object LessThanEqual {
    def schema[A]: Schema[LessThanEqual[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[A], Remote[A], LessThanEqual[A]](
          Schema.Field("left", Remote.schema[A]),
          Schema.Field("right", Remote.schema[A]),
          { case (left, right) => LessThanEqual(left, right) },
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[LessThanEqual[Any], Remote[A]] =
      Schema.Case("LessThanEqual", schema[Any], _.asInstanceOf[LessThanEqual[Any]])
  }

  final case class Equal[A](left: Remote[A], right: Remote[A]) extends Remote[Boolean] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      for {
        leftDyn  <- left.evalDynamic
        rightDyn <- right.evalDynamic
        result    = leftDyn.value == rightDyn.value && Schema.structureEquality.equal(leftDyn.schema, rightDyn.schema)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Boolean], result)
  }

  object Equal {
    def schema[A]: Schema[Equal[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[A], Remote[A], Equal[A]](
          Schema.Field("left", Remote.schema[A]),
          Schema.Field("right", Remote.schema[A]),
          { case (left, right) => Equal(left, right) },
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[Equal[Any], Remote[A]] =
      Schema.Case("Equal", schema[Any], _.asInstanceOf[Equal[Any]])
  }

  final case class Not(value: Remote[Boolean]) extends Remote[Boolean] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      value.eval.map { boolValue =>
        SchemaAndValue.fromSchemaAndValue(Schema[Boolean], !boolValue)
      }
  }

  object Not {
    val schema: Schema[Not] = Schema.defer(
      Remote
        .schema[Boolean]
        .transform(
          Not.apply,
          _.value
        )
    )

    def schemaCase[A]: Schema.Case[Not, Remote[A]] =
      Schema.Case("Not", schema, _.asInstanceOf[Not])
  }

  final case class And(left: Remote[Boolean], right: Remote[Boolean]) extends Remote[Boolean] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      for {
        lval <- left.eval
        rval <- right.eval
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Boolean], lval && rval)
  }

  object And {
    val schema: Schema[And] =
      Schema.defer(
        Schema.CaseClass2[Remote[Boolean], Remote[Boolean], And](
          Schema.Field("left", Remote.schema[Boolean]),
          Schema.Field("right", Remote.schema[Boolean]),
          { case (left, right) => And(left, right) },
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[And, Remote[A]] =
      Schema.Case("And", schema, _.asInstanceOf[And])
  }

  final case class Fold[A, B](list: Remote[List[A]], initial: Remote[B], body: EvaluatedRemoteFunction[(B, A), B])
      extends Remote[B] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      list.evalDynamic.flatMap { listDyn =>
        for {
          elemSchema <- listDyn.schema match {
                          case Schema.Sequence(schema, _, _, _, _) => ZIO.succeed(schema)
                          case _                                   => ZIO.fail(s"Fold's list did not evaluate into a sequence")
                        }
          initialDyn <- initial.evalDynamic
          result <- listDyn.value match {
                      case DynamicValue.Sequence(elemsDyn) =>
                        ZIO.foldLeft(elemsDyn)(initialDyn.value) { case (b, a) =>
                          body
                            .apply(
                              Remote
                                .Tuple2(
                                  Remote.Literal(b, initialDyn.schema),
                                  Remote.Literal(a, elemSchema.asInstanceOf[Schema[A]])
                                )
                            )
                            .evalDynamic
                            .map(_.value)
                        }
                      case _ =>
                        ZIO.fail(s"Fold's list did not evaluate into a sequence")
                    }
        } yield SchemaAndValue(initialDyn.schema, result)
      }
  }

  object Fold {
    def schema[A, B]: Schema[Fold[A, B]] =
      Schema.defer(
        Schema.CaseClass3[Remote[List[A]], Remote[B], EvaluatedRemoteFunction[(B, A), B], Fold[A, B]](
          Schema.Field("list", Remote.schema[List[A]]),
          Schema.Field("initial", Remote.schema[B]),
          Schema.Field("body", EvaluatedRemoteFunction.schema[(B, A), B]),
          { case (list, initial, body) => Fold(list, initial, body) },
          _.list,
          _.initial,
          _.body
        )
      )

    def schemaCase[A]: Schema.Case[Fold[Any, A], Remote[A]] =
      Schema.Case("fold", schema[Any, A], _.asInstanceOf[Fold[Any, A]])
  }
//
//  final case class Cons[A](list: Remote[List[A]], head: Remote[A]) extends Remote[List[A]] {
//    val schema: SchemaOrNothing.Aux[List[A]] = list.schema.asInstanceOf[SchemaOrNothing.Aux[List[A]]]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[List[A]], SchemaAndValue[List[A]]]] =
//      list.evalWithSchema.flatMap {
//        case Left(_) => ZIO.left(self)
//        case Right(schemaAndValue) =>
//          binaryEvalWithSchema(list, head)(
//            (l, h) => h :: l,
//            (remoteL, remoteH) => Cons(remoteL, remoteH),
//            SchemaOrNothing.fromSchema(schemaAndValue.schema.asInstanceOf[Schema[List[A]]])
//          )
//      }
//  }
//
//  final case class UnCons[A](list: Remote[List[A]]) extends Remote[Option[(A, List[A])]] {
//    val schema: SchemaOrNothing.Aux[Option[(A, List[A])]] = SchemaOrNothing.fromSchema {
//      val listSchema = list.schema.asInstanceOf[Schema.Sequence[List[A], A, _]]
//      Schema.option(Schema.tuple2(listSchema.schemaA, listSchema))
//    }
//
//    override def evalWithSchema
//      : ZIO[RemoteContext, Nothing, Either[Remote[Option[(A, List[A])]], SchemaAndValue[Option[(A, List[A])]]]] = {
//      implicit def toOptionSchema[T](schema: Schema[T]): Schema[Option[T]] = Schema.option(schema)
//
//      implicit def toTupleSchema[S, U](schemaS: Schema[S], schemaU: Schema[U]): Schema[(S, U)] =
//        Schema.tuple2(schemaS, schemaU)
//
//      list.evalWithSchema.map {
//        case Left(remote) => Left(UnCons(remote))
//        case Right(rightVal) =>
//          Right(rightVal.value.headOption match {
//            case Some(v) =>
//              SchemaAndValue(
//                toOptionSchema(
//                  toTupleSchema(
//                    (rightVal.schema.asInstanceOf[SchemaList[A]]) match {
//                      case Schema.Sequence(schemaA, _, _, _, _) => schemaA.asInstanceOf[Schema[A]]
//                      case _ =>
//                        throw new IllegalStateException("Every remote UnCons must be constructed using Remote[List].")
//                    },
//                    rightVal.schema.asInstanceOf[SchemaList[A]]
//                  )
//                ),
//                Some((v, rightVal.value.tail))
//              )
//            case None =>
//              val schema = rightVal.schema.asInstanceOf[SchemaList[A]]
//              SchemaAndValue(
//                toOptionSchema(
//                  toTupleSchema(
//                    schema match {
//                      case Schema.Sequence(schemaA, _, _, _, _) => schemaA.asInstanceOf[Schema[A]]
//                      case _ =>
//                        throw new IllegalStateException("Every remote UnCons must be constructed using Remote[List].")
//                    },
//                    rightVal.schema
//                  )
//                ),
//                None
//              )
//            case _ => throw new IllegalStateException("Every remote UnCons must be constructed using Remote[List].")
//          })
//      }
//    }
//  }
//
//  final case class InstantFromLong(seconds: Remote[Long]) extends Remote[Instant] {
//    val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
//      unaryEval(seconds)(s => Instant.ofEpochSecond(s), remoteS => InstantFromLong(remoteS))
//        .map(_.map(SchemaAndValue(Schema[Instant], _)))
//  }
//
//  final case class InstantFromLongs(seconds: Remote[Long], nanos: Remote[Long]) extends Remote[Instant] {
//    val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
//      binaryEval(seconds, nanos)(
//        (l1, l2) => Instant.ofEpochSecond(l1, l2),
//        (remoteS, remoteN) => InstantFromLongs(remoteS, remoteN)
//      ).map(_.map(SchemaAndValue(Schema[Instant], _)))
//  }
//
//  final case class InstantFromMilli(milliSecond: Remote[Long]) extends Remote[Instant] {
//    val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
//      unaryEval(milliSecond)(l => Instant.ofEpochMilli(l), remoteM => InstantFromMilli(remoteM))
//        .map(_.map(SchemaAndValue(Schema[Instant], _)))
//  }
//
//  final case class InstantFromClock(clock: Remote[Clock]) extends Remote[Instant] {
//    val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
//      unaryEval(clock)(c => Instant.now(c), remoteC => InstantFromClock(remoteC))
//        .map(_.map(SchemaAndValue(Schema[Instant], _)))
//  }
//
//  final case class InstantFromString(charSeq: Remote[String]) extends Remote[Instant] {
//    val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
//      unaryEval(charSeq)(chars => Instant.parse(chars), remoteC => InstantFromString(remoteC))
//        .map(_.map(SchemaAndValue(Schema[Instant], _)))
//  }
//
//  final case class InstantToTuple(instant: Remote[Instant]) extends Remote[(Long, Int)] {
//    val schema: SchemaOrNothing.Aux[(Long, Int)] = SchemaOrNothing[(Long, Int)]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[(Long, Int)], SchemaAndValue[(Long, Int)]]] =
//      unaryEval(instant)(
//        instant => (instant.getEpochSecond, instant.getNano),
//        remoteT => InstantToTuple(remoteT)
//      ).map(_.map(SchemaAndValue(Schema[(Long, Int)], _)))
//  }
//
//  final case class InstantPlusDuration(instant: Remote[Instant], duration: Remote[Duration]) extends Remote[Instant] {
//    val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
//      binaryEval(instant, duration)(
//        (i, d) => i.plusSeconds(d.getSeconds).plusNanos(d.getNano.toLong),
//        (remoteI, remoteD) => InstantPlusDuration(remoteI, remoteD)
//      ).map(_.map(SchemaAndValue(Schema[Instant], _)))
//  }
//
//  final case class InstantMinusDuration(instant: Remote[Instant], duration: Remote[Duration]) extends Remote[Instant] {
//    val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
//      binaryEval(instant, duration)(
//        (i, d) => i.minusSeconds(d.getSeconds).minusNanos(d.getNano.toLong),
//        (remoteI, remoteD) => InstantMinusDuration(remoteI, remoteD)
//      ).map(_.map(SchemaAndValue(Schema[Instant], _)))
//  }
//
//  final case class InstantTruncate(instant: Remote[Instant], tempUnit: Remote[TemporalUnit]) extends Remote[Instant] {
//    val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Instant], SchemaAndValue[Instant]]] =
//      binaryEval(instant, tempUnit)(
//        (i, t) => i.truncatedTo(t),
//        (remoteI, remoteU) => InstantTruncate(remoteI, remoteU)
//      ).map(_.map(SchemaAndValue(Schema[Instant], _)))
//  }
//
//  final case class DurationFromTemporalAmount(amount: Remote[TemporalAmount]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      unaryEval(amount)(a => Duration.from(a), remoteAmount => DurationFromTemporalAmount(remoteAmount))
//        .map(_.map(SchemaAndValue(Schema[Duration], _)))
//  }
//
//  final case class DurationFromString(charSequence: Remote[String]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      unaryEval(charSequence)(str => Duration.parse(str), remoteCS => DurationFromString(remoteCS))
//        .map(_.map(SchemaAndValue(Schema[Duration], _)))
//  }
//
//  final case class DurationFromTemporals(start: Remote[Temporal], end: Remote[Temporal]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      binaryEval(start, end)(
//        (t1, t2) => Duration.between(t1, t2),
//        (remoteT1, remoteT2) => DurationFromTemporals(remoteT1, remoteT2)
//      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
//  }
//
//  final case class DurationFromBigDecimal(seconds: Remote[BigDecimal]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    private val oneBillion = new BigDecimal(1000000000L)
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      unaryEval(seconds)(
//        bd => {
//          val seconds = bd.longValue()
//          val nanos   = bd.subtract(new BigDecimal(seconds)).multiply(oneBillion).intValue()
//          Duration.ofSeconds(seconds, nanos.toLong)
//        },
//        remoteBD => DurationFromBigDecimal(remoteBD)
//      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
//
//  }
//
//  final case class DurationFromLong(seconds: Remote[Long]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      unaryEval(seconds)(Duration.ofSeconds, remoteS => DurationFromLong(remoteS))
//        .map(_.map(SchemaAndValue(Schema[Duration], _)))
//  }
//
//  final case class DurationFromLongs(seconds: Remote[Long], nanos: Remote[Long]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      binaryEval(seconds, nanos)(
//        (seconds, nanos) => Duration.ofSeconds(seconds, nanos),
//        (remoteS, remoteN) => DurationFromLongs(remoteS, remoteN)
//      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
//  }
//
//  final case class DurationFromAmount(amount: Remote[Long], temporal: Remote[TemporalUnit]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      binaryEval(amount, temporal)(
//        (amount, unit) => Duration.of(amount, unit),
//        (remoteAmount, remoteUnit) => DurationFromAmount(remoteAmount, remoteUnit)
//      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
//  }
//
//  final case class DurationToLongs(duration: Remote[Duration]) extends Remote[(Long, Long)] {
//    val schema: SchemaOrNothing.Aux[(Long, Long)] = SchemaOrNothing[(Long, Long)]
//
//    override def evalWithSchema
//      : ZIO[RemoteContext, Nothing, Either[Remote[(Long, Long)], SchemaAndValue[(Long, Long)]]] =
//      unaryEval(duration)(
//        d => (d.getSeconds, d.getNano.toLong),
//        remoteDuration => DurationToLongs(remoteDuration)
//      ).map(_.map(SchemaAndValue(Schema[(Long, Long)], _)))
//  }
//
//  final case class DurationToLong[A](duration: Remote[Duration]) extends Remote[Long] {
//    val schema: SchemaOrNothing.Aux[Long] = SchemaOrNothing[Long]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Long], SchemaAndValue[Long]]] =
//      unaryEval(duration)(
//        _.getSeconds(),
//        remoteDuration => DurationToLong(remoteDuration)
//      ).map(_.map(SchemaAndValue(Schema[Long], _)))
//  }
//
//  final case class DurationPlusDuration(left: Remote[Duration], right: Remote[Duration]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      binaryEval(left, right)(
//        (d1, d2) => d1.plus(d2),
//        (remoteD1, remoteD2) => DurationPlusDuration(remoteD1, remoteD2)
//      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
//  }
//
//  final case class DurationMinusDuration(left: Remote[Duration], right: Remote[Duration]) extends Remote[Duration] {
//    val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Duration], SchemaAndValue[Duration]]] =
//      binaryEval(left, right)(
//        (d1, d2) => d1.minus(d2),
//        (remoteD1, remoteD2) => DurationMinusDuration(remoteD1, remoteD2)
//      ).map(_.map(SchemaAndValue(Schema[Duration], _)))
//  }
//
//  final case class Iterate[A](
//    initial: Remote[A],
//    iterate: A ===> A,
//    predicate: A ===> Boolean
//  ) extends Remote[A] {
//    val schema = iterate.schema
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] = {
//      def loop(current: Remote[A]): ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
//        predicate(current).evalWithSchema.flatMap {
//          case Left(_)      => ZIO.left(self)
//          case Right(value) => if (value.value) loop(iterate(current)) else current.evalWithSchema
//        }
//
//      loop(initial)
//    }
//  }
//
//  final case class Lazy[A] private (value: () => Remote[A], schema: SchemaOrNothing.Aux[A]) extends Remote[A] {
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
//      value().evalWithSchema
//  }
//
//  object Lazy {
//    def apply[A: SchemaOrNothing.Aux](value: () => Remote[A]): Remote[A] = {
//      lazy val remote             = value()
//      val value2: () => Remote[A] = () => remote
//      new Lazy(value2, SchemaOrNothing[A])
//    }
//  }
//
//  final case class Some0[A](value: Remote[A]) extends Remote[Option[A]] {
//    val schema = SchemaOrNothing.fromSchema(Schema.option(value.schema.schema))
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Option[A]], SchemaAndValue[Option[A]]]] =
//      value.evalWithSchema.map {
//        case Left(_) => Left(self)
//        case Right(SchemaAndValue(schema, value)) =>
//          val schemaA = schema.asInstanceOf[Schema[A]]
//          val a       = value.asInstanceOf[A]
//          Right(SchemaAndValue(Schema.Optional(schemaA), Some(a)))
//        case Right(_) => throw new IllegalStateException("Every remote Some0 must be constructed using Remote[Option].")
//      }
//  }
//
//  final case class FoldOption[A, B](option: Remote[Option[A]], remoteB: Remote[B], f: A ===> B) extends Remote[B] {
//    val schema = f.schema
//
//    def schemaFromOption[T](opSchema: Schema[Option[T]]): Schema[T] =
//      opSchema.transform(op => op.getOrElse(().asInstanceOf[T]), (value: T) => Some(value))
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[B], SchemaAndValue[B]]] =
//      option.evalWithSchema.flatMap {
//        case Left(_) => ZIO.left(self)
//        case Right(schemaAndValue) =>
//          val schemaA = schemaFromOption(schemaAndValue.schema.asInstanceOf[Schema[Option[A]]])
//          schemaAndValue.value.fold(remoteB.evalWithSchema)(v =>
//            f(Literal(v, SchemaOrNothing.fromSchema(schemaA))).evalWithSchema
//          )
//      }
//  }
//
//  final case class ZipOption[A, B](remoteA: Remote[Option[A]], remoteB: Remote[Option[B]])
//      extends Remote[Option[(A, B)]] {
//    val schema =
//      SchemaOrNothing.fromSchema(
//        Schema.option(
//          Schema.tuple2(
//            remoteA.schema.asInstanceOf[SchemaOrNothing.Aux[Option[A]]].schema.asInstanceOf[Schema.Optional[A]].codec,
//            remoteB.schema.asInstanceOf[SchemaOrNothing.Aux[Option[B]]].schema.asInstanceOf[Schema.Optional[B]].codec
//          )
//        )
//      )
//
//    override def evalWithSchema
//      : ZIO[RemoteContext, Nothing, Either[Remote[Option[(A, B)]], SchemaAndValue[Option[(A, B)]]]] =
//      for {
//        lEval <- remoteA.evalWithSchema
//        rEval <- remoteB.evalWithSchema
//        result = (lEval, rEval) match {
//                   case (Right(SchemaAndValue(schemaA, leftA)), Right(SchemaAndValue(schemaB, rightA))) =>
//                     val leftVal: Option[A]     = leftA.asInstanceOf[Option[A]]
//                     val rightVal: Option[B]    = rightA.asInstanceOf[Option[B]]
//                     val schemaAInst: Schema[A] = schemaA.asInstanceOf[Schema[A]]
//                     val schemaBInst: Schema[B] = schemaB.asInstanceOf[Schema[B]]
//
//                     val value = if (leftVal.isEmpty || rightVal.isEmpty) None else Some((leftVal.get, rightVal.get))
//                     Right(
//                       SchemaAndValue(Schema.Optional(Schema.Tuple(schemaAInst, schemaBInst)), value)
//                     )
//                   case _ => Left(Remote(None))
//                 }
//      } yield result
//  }
//
//  final case class ContainsOption[A](left: Remote[Option[A]], right: A) extends Remote[Boolean] {
//    val schema: SchemaOrNothing.Aux[Boolean] = SchemaOrNothing[Boolean]
//
//    override def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Boolean], SchemaAndValue[Boolean]]] =
//      left.evalWithSchema.map {
//        case (Right(SchemaAndValue(schemaA, leftA))) =>
//          val leftVal: Option[A]     = leftA.asInstanceOf[Option[A]]
//          val schemaAInst: Schema[A] = schemaA.asInstanceOf[Schema[A]]
//          val value: Literal[A]      = Literal(right, SchemaOrNothing.fromSchema(schemaAInst))
//          Right(
//            SchemaAndValue(Schema[Boolean], leftVal.contains(value.value))
//          )
//        case _ => Left(Remote(false))
//      }
//  }
//
//  final case class LensGet[S, A](whole: Remote[S], lens: RemoteLens[S, A]) extends Remote[A] {
//    val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(lens.schemaPiece)
//
//    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
//      whole.evalWithSchema.map {
//        case Right(SchemaAndValue(_, whole)) => Right(SchemaAndValue(lens.schemaPiece, lens.unsafeGet(whole)))
//        case _                               => Left(self)
//      }
//  }
//
//  final case class LensSet[S, A](whole: Remote[S], piece: Remote[A], lens: RemoteLens[S, A]) extends Remote[S] {
//    val schema: SchemaOrNothing.Aux[S] = SchemaOrNothing.fromSchema(lens.schemaWhole)
//
//    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
//      whole.evalWithSchema.flatMap {
//        case Right(SchemaAndValue(_, whole)) =>
//          piece.evalWithSchema.flatMap {
//            case Right(SchemaAndValue(_, piece)) =>
//              val newValue = lens.unsafeSet(piece)(whole)
//              ZIO.right(SchemaAndValue(lens.schemaWhole, newValue))
//            case _ => ZIO.left(self)
//          }
//        case _ => ZIO.left(self)
//      }
//  }
//
//  final case class PrismGet[S, A](whole: Remote[S], prism: RemotePrism[S, A]) extends Remote[Option[A]] {
//    val schema: SchemaOrNothing.Aux[Option[A]] = SchemaOrNothing.fromSchema(Schema.option(prism.schemaPiece))
//
//    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Option[A]], SchemaAndValue[Option[A]]]] =
//      whole.evalWithSchema.map {
//        case Right(SchemaAndValue(_, whole)) =>
//          Right(SchemaAndValue(Schema.option(prism.schemaPiece), prism.unsafeGet(whole)))
//        case _ => Left(self)
//      }
//  }
//
//  final case class PrismSet[S, A](piece: Remote[A], prism: RemotePrism[S, A]) extends Remote[S] {
//    val schema: SchemaOrNothing.Aux[S] = SchemaOrNothing.fromSchema(prism.schemaWhole)
//
//    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
//      piece.evalWithSchema.map {
//        case Right(SchemaAndValue(_, piece)) => Right(SchemaAndValue(prism.schemaWhole, prism.unsafeSet(piece)))
//        case _                               => Left(self)
//      }
//  }
//
//  final case class TraversalGet[S, A](whole: Remote[S], traversal: RemoteTraversal[S, A]) extends Remote[Chunk[A]] {
//    val schema: SchemaOrNothing.Aux[Chunk[A]] = SchemaOrNothing.fromSchema(Schema.chunk(traversal.schemaPiece))
//
//    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[Chunk[A]], SchemaAndValue[Chunk[A]]]] =
//      whole.evalWithSchema.map {
//        case Right(SchemaAndValue(_, whole)) =>
//          Right(SchemaAndValue(Schema.chunk(traversal.schemaPiece), traversal.unsafeGet(whole)))
//        case _ => Left(self)
//      }
//  }
//
//  final case class TraversalSet[S, A](whole: Remote[S], piece: Remote[Chunk[A]], traversal: RemoteTraversal[S, A])
//      extends Remote[S] {
//    val schema: SchemaOrNothing.Aux[S] = SchemaOrNothing.fromSchema(traversal.schemaWhole)
//
//    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
//      whole.evalWithSchema.flatMap {
//        case Right(SchemaAndValue(_, whole)) =>
//          piece.evalWithSchema.flatMap {
//            case Right(SchemaAndValue(_, piece)) =>
//              val newValue = traversal.unsafeSet(whole)(piece)
//              ZIO.right(SchemaAndValue(traversal.schemaWhole, newValue))
//            case _ => ZIO.left(self)
//          }
//        case _ => ZIO.left(self)
//      }
//  }

  implicit def apply[A: Schema](value: A): Remote[A] =
    Literal(DynamicValue.fromSchemaAndValue(SchemaOrNothing[A].schema, value), SchemaOrNothing[A].schema)

  def sequenceEither[A, B](
    either: Either[Remote[A], Remote[B]]
  )(implicit aSchema: SchemaOrNothing.Aux[A], bSchema: SchemaOrNothing.Aux[B]): Remote[Either[A, B]] =
    Either0(either match {
      case Left(l)  => Left((l, bSchema))
      case Right(r) => Right((aSchema, r))
    })

//  def ofEpochSecond(second: Remote[Long]): Remote[Instant] = Remote.InstantFromLong(second)
//
//  def ofEpochSecond(second: Remote[Long], nanos: Remote[Long]): Remote[Instant] = Remote.InstantFromLongs(second, nanos)
//
//  def ofEpochMilli(milliSecond: Remote[Long]): Remote[Instant] = Remote.InstantFromMilli(milliSecond)
//
//  def ofSeconds(seconds: Remote[Long]): Remote[Duration] = Remote.DurationFromLong(seconds)
//
//  def ofSeconds(seconds: Remote[Long], nanos: Remote[Long]): Remote[Duration] = Remote.DurationFromLongs(seconds, nanos)
//
//  def ofMinutes(minutes: Remote[Long]): Remote[Duration] = Remote.ofSeconds(minutes * Remote(60L))
//
//  def ofHours(hours: Remote[Long]): Remote[Duration] = Remote.ofMinutes(hours * Remote(60L))
//
//  def ofDays(days: Remote[Long]): Remote[Duration] = Remote.ofHours(days * Remote(24L))
//
//  def ofMillis(milliseconds: Remote[Long]): Remote[Duration] =
//    Remote.DurationFromAmount(milliseconds, Remote(ChronoUnit.MILLIS))
//
//  def ofNanos(nanoseconds: Remote[Long]): Remote[Duration] =
//    Remote.DurationFromAmount(nanoseconds, Remote(ChronoUnit.NANOS))
//
  implicit def tuple2[A, B](t: (Remote[A], Remote[B])): Remote[(A, B)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[A, B, C](t: (Remote[A], Remote[B], Remote[C])): Remote[(A, B, C)] =
    Tuple3(t._1, t._2, t._3)

  implicit def tuple4[A, B, C, D](t: (Remote[A], Remote[B], Remote[C], Remote[D])): Remote[(A, B, C, D)] =
    Tuple4(t._1, t._2, t._3, t._4)

//  def suspend[A: Schema](remote: Remote[A]): Remote[A] = Lazy(() => remote)

//  implicit def toFlow[A](remote: Remote[A]): ZFlow[Any, Nothing, A] = remote.toFlow

  implicit def capturedRemoteToRemote[A: SchemaOrNothing.Aux, B](f: Remote[A] => Remote[B]): RemoteFunction[A, B] =
    RemoteFunction((a: Remote[A]) => f(a))

//  implicit def capturedRemoteToFlow[A: Schema, R, E, B](f: Remote[A] => ZFlow[R, E, B]): RemoteFunction[A, ZFlow[R, E, B]] =
//    RemoteFunction((a: Remote[A]) => Remote(f(a)))

  val unit: Remote[Unit] = Remote.Ignore()

  def schema[A]: Schema[Remote[A]] = Schema.EnumN(
    CaseSet
      .Cons(Literal.schemaCase[A], CaseSet.Empty[Remote[A]]())
      .:+:(Ignore.schemaCase)
      .:+:(Variable.schemaCase[A])
      .:+:(AddNumeric.schemaCase[A])
      .:+:(EvaluatedRemoteFunction.schemaCase[Any, A])
      .:+:(RemoteApply.schemaCase[Any, A])
      .:+:(DivNumeric.schemaCase[A])
      .:+:(MulNumeric.schemaCase[A])
      .:+:(PowNumeric.schemaCase[A])
      .:+:(NegationNumeric.schemaCase[A])
      .:+:(RootNumeric.schemaCase[A])
      .:+:(LogNumeric.schemaCase[A])
      .:+:(ModNumeric.schemaCase[A])
      .:+:(AbsoluteNumeric.schemaCase[A])
      .:+:(MinNumeric.schemaCase[A])
      .:+:(MaxNumeric.schemaCase[A])
      .:+:(FloorNumeric.schemaCase[A])
      .:+:(CeilNumeric.schemaCase[A])
      .:+:(RoundNumeric.schemaCase[A])
      .:+:(SinFractional.schemaCase[A])
      .:+:(SinInverseFractional.schemaCase[A])
      .:+:(TanInverseFractional.schemaCase[A])
      .:+:(Either0.schemaCase[A])
      .:+:(FlatMapEither.schemaCase[A])
      .:+:(FoldEither.schemaCase[Any, Any, A])
      .:+:(SwapEither.schemaCase[A])
      .:+:(Try.schemaCase[A])
      .:+:(Tuple2.schemaCase[A])
      .:+:(Tuple3.schemaCase[A])
      .:+:(Tuple4.schemaCase[A])
      .:+:(First.schemaCase[A])
      .:+:(Second.schemaCase[A])
      .:+:(FirstOf3.schemaCase[A])
      .:+:(SecondOf3.schemaCase[A])
      .:+:(ThirdOf3.schemaCase[A])
      .:+:(FirstOf4.schemaCase[A])
      .:+:(SecondOf4.schemaCase[A])
      .:+:(ThirdOf4.schemaCase[A])
      .:+:(FourthOf4.schemaCase[A])
      .:+:(Branch.schemaCase[A])
      .:+:(Length.schemaCase[A])
      .:+:(LessThanEqual.schemaCase[A])
      .:+:(Equal.schemaCase[A])
      .:+:(Not.schemaCase[A])
      .:+:(And.schemaCase[A])
      .:+:(Fold.schemaCase[A])
  )

  implicit val schemaRemoteAny: Schema[Remote[Any]] = schema[Any]
}
