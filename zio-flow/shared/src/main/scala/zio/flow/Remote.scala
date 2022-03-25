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

import zio.ZIO
import zio.flow.remote.Numeric.NumericInt
import zio.flow.remote._
import zio.schema.ast.SchemaAst
import zio.schema.{CaseSet, DynamicValue, Schema, StandardType}

import java.math.BigDecimal
import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
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

  def schema: SchemaOrNothing.Aux[_ <: A]

  final def iterate[A1 >: A: Schema](
    step: Remote[A1] => Remote[A1]
  )(predicate: Remote[A1] => Remote[Boolean]): Remote[A1] =
    predicate(self).ifThenElse(
      step(self).iterate(step)(predicate),
      self
    )

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

  final case class Literal[A](value: DynamicValue, schemaA: Schema[A]) extends Remote[A] {

    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(schemaA)

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      ZIO.succeed(SchemaAndValue(schemaA, value))

    override def eval[A1 >: A](implicit schemaA1: Schema[A1]): ZIO[RemoteContext, String, A1] =
      ZIO.fromEither(value.toTypedValue(schemaA1))

    override def equals(that: Any): Boolean =
      that match {
        case Literal(otherValue, otherSchema) =>
          value == otherValue && Schema.structureEquality.equal(schemaA, otherSchema)
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
          (lit: Literal[A]) => lit.value.toTypedValue(lit.schemaA).map((_, lit.schemaA))
        )

    def schemaCase[A]: Schema.Case[Literal[A], Remote[A]] =
      Schema.Case("Literal", schema[A], _.asInstanceOf[Literal[A]])
  }

  final case class Ignore() extends Remote[Unit] {
    override val schema: SchemaOrNothing.Aux[Unit] = SchemaOrNothing.fromSchema[Unit]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Unit]] =
      ZIO.succeed(SchemaAndValue(Schema.primitive[Unit], DynamicValue.Primitive((), StandardType.UnitType)))
  }
  object Ignore {
    val schema: Schema[Ignore] = Schema[Unit].transform(_ => Ignore(), _ => ())

    def schemaCase[A]: Schema.Case[Ignore, Remote[A]] =
      Schema.Case("Ignore", schema, _.asInstanceOf[Ignore])
  }

  final case class Variable[A](identifier: RemoteVariableName, schemaA: Schema[A]) extends Remote[A] {
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(schemaA)

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      RemoteContext.getVariable(identifier).flatMap {
        case None        => ZIO.fail(s"Could not find identifier $identifier")
        case Some(value) => ZIO.succeed(SchemaAndValue(schemaA, value))
      }

    override def equals(that: Any): Boolean =
      that match {
        case Variable(otherIdentifier, otherSchema) =>
          otherIdentifier == identifier && Schema.structureEquality.equal(schemaA, otherSchema)
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
        extractField2 = (variable: Variable[A]) => SchemaAst.fromSchema(variable.schemaA)
      )

    def schemaCase[A]: Schema.Case[Variable[A], Remote[A]] =
      Schema.Case("Variable", schema, _.asInstanceOf[Variable[A]])
  }

  final case class EvaluatedRemoteFunction[A, B] private[flow] (
    input: Variable[A],
    result: Remote[B]
  ) extends Remote[B] {
    override val schema = result.schema

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
    override lazy val schema = evaluated.result.schema

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
    override val schema = f.schema

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[Int] = SchemaOrNothing.fromSchema[Int]

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(numeric.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(fractional.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(fractional.schema)

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
    override val schema: SchemaOrNothing.Aux[A] = SchemaOrNothing.fromSchema(fractional.schema)

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
    override val schema: SchemaOrNothing.Aux[_ <: Either[A, B]] =
      SchemaOrNothing.fromSchema(either match {
        case Left((r, s))  => Schema.either(r.schema.schema, s.schema)
        case Right((s, r)) => Schema.either(s.schema, r.schema.schema)
      })

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
            ZIO.fromEither(evaluatedRight.toTyped).map { rightValue =>
              SchemaAndValue.fromSchemaAndValue[Either[A, B]](
                Schema.either[A, B](
                  leftSchema.schema,
                  evaluatedRight.schema.asInstanceOf[Schema[B]]
                ),
                Right(rightValue)
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

    override val schema: SchemaOrNothing.Aux[Either[A, C]] =
      SchemaOrNothing.fromSchema(Schema.either(aSchema.schema, cSchema.schema))

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Either[A, C]]] =
      either.eval[Either[A, B]](Schema.either(aSchema.schema, f.input.schemaA)).flatMap {
        case Left(a) =>
          ZIO.succeed(SchemaAndValue.fromSchemaAndValue(Schema.either(aSchema.schema, cSchema.schema), Left(a)))
        case Right(b) =>
          f(Remote(b)(f.input.schemaA)).evalDynamic
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
    override val schema = left.schema

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[C]] =
      either.eval(Schema.either(left.input.schemaA, right.input.schemaA)).flatMap {
        case Left(a) =>
          left(Remote(a)(left.input.schemaA)).evalDynamic
        case Right(b) =>
          right(Remote(b)(right.input.schemaA)).evalDynamic
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
    override val schema: SchemaOrNothing.Aux[Either[B, A]] =
      SchemaOrNothing.fromSchema(
        Schema.either(
          either.schema.schema.asInstanceOf[Schema.EitherSchema[A, B]].right,
          either.schema.schema.asInstanceOf[Schema.EitherSchema[A, B]].left
        )
      )

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
    override val schema: SchemaOrNothing.Aux[scala.util.Try[A]] = SchemaOrNothing.fromSchema {
      val schemaA = either match {
        case Left((_, schema)) => schema
        case Right(remote)     => remote.schema.asInstanceOf[SchemaOrNothing.Aux[A]]
      }
      Schema
        .either(Schema[Throwable], schemaA.schema)
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
        case _ => false
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
    override val schema: SchemaOrNothing.Aux[_ <: (A, B)] =
      SchemaOrNothing.fromSchema(
        Schema.tuple2(left.schema.schema, right.schema.schema)
      )

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
    override val schema: SchemaOrNothing.Aux[_ <: (A, B, C)] =
      SchemaOrNothing.fromSchema(Schema.tuple3(_1.schema.schema, _2.schema.schema, _3.schema.schema))

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

    override val schema: SchemaOrNothing.Aux[_ <: (A, B, C, D)] =
      SchemaOrNothing.fromSchema(
        Schema.tuple4(_1.schema.schema, _2.schema.schema, _3.schema.schema, _4.schema.schema)
      )

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
    override val schema: SchemaOrNothing.Aux[A] =
      SchemaOrNothing.fromSchema(
        tuple.schema.schema.asInstanceOf[Schema.Tuple[A, B]].left
      )

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
    override val schema: SchemaOrNothing.Aux[B] =
      SchemaOrNothing.fromSchema(tuple.schema.schema.asInstanceOf[Schema.Tuple[A, B]].right)

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
    override val schema: SchemaOrNothing.Aux[A] =
      SchemaOrNothing.fromSchema(
        tuple.schema.schema
          .asInstanceOf[Schema.Transform[((A, B), C), _, _]]
          .codec
          .asInstanceOf[Schema.Tuple[(A, B), C]]
          .left
          .asInstanceOf[Schema.Tuple[A, B]]
          .left
      )

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

    override val schema: SchemaOrNothing.Aux[B] =
      SchemaOrNothing.fromSchema(
        tuple.schema.schema
          .asInstanceOf[Schema.Transform[((A, B), C), _, _]]
          .codec
          .asInstanceOf[Schema.Tuple[(A, B), C]]
          .left
          .asInstanceOf[Schema.Tuple[A, B]]
          .right
      )

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

    override val schema: SchemaOrNothing.Aux[C] =
      SchemaOrNothing.fromSchema(
        tuple.schema.schema
          .asInstanceOf[Schema.Transform[((A, B), C), _, _]]
          .codec
          .asInstanceOf[Schema.Tuple[(A, B), C]]
          .right
      )

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
    override val schema: SchemaOrNothing.Aux[A] =
      SchemaOrNothing.fromSchema(
        tuple.schema.schema
          .asInstanceOf[Schema.Transform[(((A, B), C), D), _, _]]
          .codec
          .asInstanceOf[Schema.Tuple[((A, B), C), D]]
          .left
          .asInstanceOf[Schema.Tuple[(A, B), C]]
          .left
          .asInstanceOf[Schema.Tuple[A, B]]
          .left
      )

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
    override val schema: SchemaOrNothing.Aux[B] =
      SchemaOrNothing.fromSchema(
        tuple.schema.schema
          .asInstanceOf[Schema.Transform[(((A, B), C), D), _, _]]
          .codec
          .asInstanceOf[Schema.Tuple[((A, B), C), D]]
          .left
          .asInstanceOf[Schema.Tuple[(A, B), C]]
          .left
          .asInstanceOf[Schema.Tuple[A, B]]
          .right
      )

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
    override val schema: SchemaOrNothing.Aux[C] =
      SchemaOrNothing.fromSchema(
        tuple.schema.schema
          .asInstanceOf[Schema.Transform[(((A, B), C), D), _, _]]
          .codec
          .asInstanceOf[Schema.Tuple[((A, B), C), D]]
          .left
          .asInstanceOf[Schema.Tuple[(A, B), C]]
          .right
      )

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
    override val schema: SchemaOrNothing.Aux[D] =
      SchemaOrNothing.fromSchema(
        tuple.schema.schema
          .asInstanceOf[Schema.Transform[(((A, B), C), D), _, _]]
          .codec
          .asInstanceOf[Schema.Tuple[((A, B), C), D]]
          .right
      )

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
    override val schema: SchemaOrNothing.Aux[A] =
      ifTrue.schema.asInstanceOf[SchemaOrNothing.Aux[A]]

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
    override val schema: SchemaOrNothing.Aux[Int] = SchemaOrNothing[Int]

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
    override val schema: SchemaOrNothing.Aux[Boolean] = SchemaOrNothing[Boolean]

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
    override val schema: SchemaOrNothing.Aux[Boolean] = SchemaOrNothing[Boolean]

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
    override val schema: SchemaOrNothing.Aux[Boolean] = SchemaOrNothing[Boolean]

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
    override val schema: SchemaOrNothing.Aux[Boolean] = SchemaOrNothing[Boolean]

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
    override val schema = body.schema

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
      Schema.Case("Fold", schema[Any, A], _.asInstanceOf[Fold[Any, A]])
  }

  final case class Cons[A](list: Remote[List[A]], head: Remote[A]) extends Remote[List[A]] {

    override val schema: SchemaOrNothing.Aux[List[A]] = list.schema.asInstanceOf[SchemaOrNothing.Aux[List[A]]]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[List[A]]] =
      head.evalDynamic.flatMap { headDyn =>
        val listSchema = Schema.list(headDyn.schema.asInstanceOf[Schema[A]])
        list.eval(listSchema).flatMap { lst =>
          ZIO.fromEither(headDyn.toTyped).map { hd =>
            val updatedLst = hd :: lst
            SchemaAndValue.fromSchemaAndValue(listSchema, updatedLst)
          }
        }
      }
  }

  object Cons {
    def schema[A]: Schema[Cons[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[List[A]], Remote[A], Cons[A]](
          Schema.Field("list", Remote.schema[List[A]]),
          Schema.Field("head", Remote.schema[A]),
          Cons.apply,
          _.list,
          _.head
        )
      )

    def schemaCase[A]: Schema.Case[Cons[A], Remote[A]] =
      Schema.Case("Cons", schema[A], _.asInstanceOf[Cons[A]])
  }

  final case class UnCons[A](list: Remote[List[A]]) extends Remote[Option[(A, List[A])]] {

    override val schema: SchemaOrNothing.Aux[Option[(A, List[A])]] = SchemaOrNothing.fromSchema {
      val listSchema = list.schema.schema.asInstanceOf[Schema.Sequence[List[A], A, _]]
      Schema.option(Schema.tuple2(listSchema.schemaA, listSchema))
    }

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Option[(A, List[A])]]] =
      list.evalDynamic.flatMap { listDyn =>
        val listSchema = listDyn.schema.asInstanceOf[Schema.Sequence[List[A], A, _]]
        val elemSchema = listSchema.schemaA
        ZIO.fromEither(listDyn.toTyped).map { lst =>
          val head  = lst.headOption
          val tuple = head.map((_, lst))
          SchemaAndValue.fromSchemaAndValue(Schema.option(Schema.tuple2(elemSchema, listDyn.schema)), tuple)
        }
      }
  }

  object UnCons {
    def schema[A]: Schema[UnCons[A]] = Schema.defer(
      Remote
        .schema[List[A]]
        .transform(
          UnCons.apply,
          _.list
        )
    )

    def schemaCase[A]: Schema.Case[UnCons[A], Remote[A]] =
      Schema.Case("UnCons", schema[A], _.asInstanceOf[UnCons[A]])
  }

  final case class InstantFromLong(seconds: Remote[Long]) extends Remote[Instant] {
    override val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      seconds.eval[Long].map(s => SchemaAndValue.fromSchemaAndValue(Schema[Instant], Instant.ofEpochSecond(s)))
  }

  object InstantFromLong {
    val schema: Schema[InstantFromLong] = Schema.defer(
      Remote
        .schema[Long]
        .transform(
          InstantFromLong.apply,
          _.seconds
        )
    )

    def schemaCase[A]: Schema.Case[InstantFromLong, Remote[A]] =
      Schema.Case("InstantFromLong", schema, _.asInstanceOf[InstantFromLong])
  }

  final case class InstantFromLongs(seconds: Remote[Long], nanos: Remote[Long]) extends Remote[Instant] {
    override val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      for {
        s <- seconds.eval[Long]
        n <- nanos.eval[Long]
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Instant], Instant.ofEpochSecond(s, n))
  }

  object InstantFromLongs {

    val schema: Schema[InstantFromLongs] =
      Schema.defer(
        Schema.CaseClass2[Remote[Long], Remote[Long], InstantFromLongs](
          Schema.Field("seconds", Remote.schema[Long]),
          Schema.Field("nanos", Remote.schema[Long]),
          InstantFromLongs.apply,
          _.seconds,
          _.nanos
        )
      )

    def schemaCase[A]: Schema.Case[InstantFromLongs, Remote[A]] =
      Schema.Case("InstantFromLongs", schema, _.asInstanceOf[InstantFromLongs])
  }

  final case class InstantFromMilli(millis: Remote[Long]) extends Remote[Instant] {
    override val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      millis.eval[Long].map(s => SchemaAndValue.fromSchemaAndValue(Schema[Instant], Instant.ofEpochMilli(s)))
  }

  object InstantFromMilli {
    val schema: Schema[InstantFromMilli] = Schema.defer(
      Remote
        .schema[Long]
        .transform(
          InstantFromMilli.apply,
          _.millis
        )
    )

    def schemaCase[A]: Schema.Case[InstantFromMilli, Remote[A]] =
      Schema.Case("InstantFromMilli", schema, _.asInstanceOf[InstantFromMilli])
  }

  final case class InstantFromString(charSeq: Remote[String]) extends Remote[Instant] {
    override val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      charSeq.eval[String].map(s => SchemaAndValue.fromSchemaAndValue(Schema[Instant], Instant.parse(s)))
  }

  object InstantFromString {
    val schema: Schema[InstantFromString] = Schema.defer(
      Remote
        .schema[String]
        .transform(
          InstantFromString.apply,
          _.charSeq
        )
    )

    def schemaCase[A]: Schema.Case[InstantFromString, Remote[A]] =
      Schema.Case("InstantFromString", schema, _.asInstanceOf[InstantFromString])
  }

  final case class InstantToTuple(instant: Remote[Instant]) extends Remote[(Long, Int)] {
    override val schema: SchemaOrNothing.Aux[(Long, Int)] = SchemaOrNothing[(Long, Int)]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[(Long, Int)]] =
      instant.eval[Instant].map { instant =>
        SchemaAndValue
          .fromSchemaAndValue(Schema.tuple2(Schema[Long], Schema[Int]), (instant.getEpochSecond, instant.getNano))
      }
  }

  object InstantToTuple {
    val schema: Schema[InstantToTuple] = Schema.defer(
      Remote
        .schema[Instant]
        .transform(
          InstantToTuple.apply,
          _.instant
        )
    )

    def schemaCase[A]: Schema.Case[InstantToTuple, Remote[A]] =
      Schema.Case("InstantToTuple", schema, _.asInstanceOf[InstantToTuple])
  }

  final case class InstantPlusDuration(instant: Remote[Instant], duration: Remote[Duration]) extends Remote[Instant] {
    override val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      for {
        instant  <- instant.eval[Instant]
        duration <- duration.eval[Duration]
        result    = instant.plusSeconds(duration.getSeconds).plusNanos(duration.getNano.toLong)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Instant], result)
  }

  object InstantPlusDuration {
    val schema: Schema[InstantPlusDuration] =
      Schema.defer(
        Schema.CaseClass2[Remote[Instant], Remote[Duration], InstantPlusDuration](
          Schema.Field("instant", Remote.schema[Instant]),
          Schema.Field("duration", Remote.schema[Duration]),
          InstantPlusDuration.apply,
          _.instant,
          _.duration
        )
      )

    def schemaCase[A]: Schema.Case[InstantPlusDuration, Remote[A]] =
      Schema.Case("InstantPlusDuration", schema, _.asInstanceOf[InstantPlusDuration])
  }

  final case class InstantMinusDuration(instant: Remote[Instant], duration: Remote[Duration]) extends Remote[Instant] {
    override val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      for {
        instant  <- instant.eval[Instant]
        duration <- duration.eval[Duration]
        result    = instant.minusSeconds(duration.getSeconds).minusNanos(duration.getNano.toLong)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Instant], result)
  }

  object InstantMinusDuration {
    val schema: Schema[InstantMinusDuration] =
      Schema.defer(
        Schema.CaseClass2[Remote[Instant], Remote[Duration], InstantMinusDuration](
          Schema.Field("instant", Remote.schema[Instant]),
          Schema.Field("duration", Remote.schema[Duration]),
          InstantMinusDuration.apply,
          _.instant,
          _.duration
        )
      )

    def schemaCase[A]: Schema.Case[InstantMinusDuration, Remote[A]] =
      Schema.Case("InstantMinusDuration", schema, _.asInstanceOf[InstantMinusDuration])
  }

  final case class InstantTruncate(instant: Remote[Instant], temporalUnit: Remote[ChronoUnit]) extends Remote[Instant] {
    override val schema: SchemaOrNothing.Aux[Instant] = SchemaOrNothing[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      for {
        instant      <- instant.eval[Instant]
        _            <- ZIO.debug(s"temporal unit is $temporalUnit")
        temporalUnit <- temporalUnit.eval[ChronoUnit].tapError(s => ZIO.debug(s"Failed to evaluate temporal unit: $s"))
        result        = instant.truncatedTo(temporalUnit)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Instant], result)
  }

  object InstantTruncate {
    val schema: Schema[InstantTruncate] =
      Schema.defer(
        Schema.CaseClass2[Remote[Instant], Remote[ChronoUnit], InstantTruncate](
          Schema.Field("instant", Remote.schema[Instant]),
          Schema.Field("temporalUnit", Remote.schema[ChronoUnit]),
          InstantTruncate.apply,
          _.instant,
          _.temporalUnit
        )
      )

    def schemaCase[A]: Schema.Case[InstantTruncate, Remote[A]] =
      Schema.Case("InstantTruncate", schema, _.asInstanceOf[InstantTruncate])
  }

  final case class DurationFromString(charSeq: Remote[String]) extends Remote[Duration] {
    override val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      charSeq.eval[String].map(s => SchemaAndValue.fromSchemaAndValue(Schema[Duration], Duration.parse(s)))
  }

  object DurationFromString {
    val schema: Schema[DurationFromString] = Schema.defer(
      Remote
        .schema[String]
        .transform(
          DurationFromString.apply,
          _.charSeq
        )
    )

    def schemaCase[A]: Schema.Case[DurationFromString, Remote[A]] =
      Schema.Case("DurationFromString", schema, _.asInstanceOf[DurationFromString])
  }

  final case class DurationBetweenInstants(startInclusive: Remote[Instant], endExclusive: Remote[Instant])
      extends Remote[Duration] {
    override val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        start <- startInclusive.eval[Instant]
        end   <- endExclusive.eval[Instant]
        result = Duration.between(start, end)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Duration], result)
  }

  object DurationBetweenInstants {
    val schema: Schema[DurationBetweenInstants] =
      Schema.defer(
        Schema.CaseClass2[Remote[Instant], Remote[Instant], DurationBetweenInstants](
          Schema.Field("startInclusive", Remote.schema[Instant]),
          Schema.Field("endExclusive", Remote.schema[Instant]),
          DurationBetweenInstants.apply,
          _.startInclusive,
          _.endExclusive
        )
      )

    def schemaCase[A]: Schema.Case[DurationBetweenInstants, Remote[A]] =
      Schema.Case("DurationBetweenInstants", schema, _.asInstanceOf[DurationBetweenInstants])
  }

  final case class DurationFromBigDecimal(seconds: Remote[BigDecimal]) extends Remote[Duration] {

    override val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        bd     <- seconds.eval[BigDecimal]
        seconds = bd.longValue()
        nanos   = bd.subtract(new BigDecimal(seconds)).multiply(DurationFromBigDecimal.oneBillion).intValue()
        result  = Duration.ofSeconds(seconds, nanos.toLong)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Duration], result)
  }

  object DurationFromBigDecimal {
    private val oneBillion = new BigDecimal(1000000000L)

    val schema: Schema[DurationFromBigDecimal] = Schema.defer(
      Remote
        .schema[BigDecimal]
        .transform(
          DurationFromBigDecimal.apply,
          _.seconds
        )
    )

    def schemaCase[A]: Schema.Case[DurationFromBigDecimal, Remote[A]] =
      Schema.Case("DurationFromBigDecimal", schema, _.asInstanceOf[DurationFromBigDecimal])
  }

  final case class DurationFromLong(seconds: Remote[Long]) extends Remote[Duration] {
    override val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      seconds
        .eval[Long]
        .map(seconds => SchemaAndValue.fromSchemaAndValue(Schema[Duration], Duration.ofSeconds(seconds)))
  }

  object DurationFromLong {
    val schema: Schema[DurationFromLong] = Schema.defer(
      Remote
        .schema[Long]
        .transform(
          DurationFromLong.apply,
          _.seconds
        )
    )

    def schemaCase[A]: Schema.Case[DurationFromLong, Remote[A]] =
      Schema.Case("DurationFromLong", schema, _.asInstanceOf[DurationFromLong])
  }

  final case class DurationFromLongs(seconds: Remote[Long], nanoAdjustment: Remote[Long]) extends Remote[Duration] {
    override val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        seconds        <- seconds.eval[Long]
        nanoAdjustment <- nanoAdjustment.eval[Long]
        result          = Duration.ofSeconds(seconds, nanoAdjustment)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Duration], result)
  }

  object DurationFromLongs {
    val schema: Schema[DurationFromLongs] =
      Schema.defer(
        Schema.CaseClass2[Remote[Long], Remote[Long], DurationFromLongs](
          Schema.Field("seconds", Remote.schema[Long]),
          Schema.Field("nanoAdjusment", Remote.schema[Long]),
          DurationFromLongs.apply,
          _.seconds,
          _.nanoAdjustment
        )
      )

    def schemaCase[A]: Schema.Case[DurationFromLongs, Remote[A]] =
      Schema.Case("DurationFromLongs", schema, _.asInstanceOf[DurationFromLongs])
  }

  final case class DurationFromAmount(amount: Remote[Long], temporalUnit: Remote[ChronoUnit]) extends Remote[Duration] {
    override val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        amount       <- amount.eval[Long]
        temporalUnit <- temporalUnit.eval[ChronoUnit]
        result        = Duration.of(amount, temporalUnit)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Duration], result)
  }

  object DurationFromAmount {
    val schema: Schema[DurationFromAmount] =
      Schema.defer(
        Schema.CaseClass2[Remote[Long], Remote[ChronoUnit], DurationFromAmount](
          Schema.Field("amount", Remote.schema[Long]),
          Schema.Field("temporalUnit", Remote.schema[ChronoUnit]),
          DurationFromAmount.apply,
          _.amount,
          _.temporalUnit
        )
      )

    def schemaCase[A]: Schema.Case[DurationFromAmount, Remote[A]] =
      Schema.Case("DurationFromAmount", schema, _.asInstanceOf[DurationFromAmount])
  }

  final case class DurationToLongs(duration: Remote[Duration]) extends Remote[(Long, Long)] {
    override val schema: SchemaOrNothing.Aux[(Long, Long)] = SchemaOrNothing[(Long, Long)]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[(Long, Long)]] =
      duration.eval[Duration].map { duration =>
        SchemaAndValue
          .fromSchemaAndValue(Schema.tuple2(Schema[Long], Schema[Long]), (duration.getSeconds, duration.getNano.toLong))
      }
  }

  object DurationToLongs {
    val schema: Schema[DurationToLongs] = Schema.defer(
      Remote
        .schema[Duration]
        .transform(
          DurationToLongs.apply,
          _.duration
        )
    )

    def schemaCase[A]: Schema.Case[DurationToLongs, Remote[A]] =
      Schema.Case("DurationToLongs", schema, _.asInstanceOf[DurationToLongs])
  }

  final case class DurationToLong(duration: Remote[Duration]) extends Remote[Long] {
    override val schema: SchemaOrNothing.Aux[Long] = SchemaOrNothing[Long]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Long]] =
      duration.eval[Duration].map { duration =>
        SchemaAndValue.fromSchemaAndValue(Schema[Long], duration.getSeconds)
      }
  }

  object DurationToLong {
    val schema: Schema[DurationToLong] = Schema.defer(
      Remote
        .schema[Duration]
        .transform(
          DurationToLong.apply,
          _.duration
        )
    )

    def schemaCase[A]: Schema.Case[DurationToLong, Remote[A]] =
      Schema.Case("DurationToLong", schema, _.asInstanceOf[DurationToLong])
  }

  final case class DurationPlusDuration(left: Remote[Duration], right: Remote[Duration]) extends Remote[Duration] {
    override val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        left  <- left.eval[Duration]
        right <- right.eval[Duration]
        result = left.plus(right)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Duration], result)
  }

  object DurationPlusDuration {
    val schema: Schema[DurationPlusDuration] =
      Schema.defer(
        Schema.CaseClass2[Remote[Duration], Remote[Duration], DurationPlusDuration](
          Schema.Field("left", Remote.schema[Duration]),
          Schema.Field("right", Remote.schema[Duration]),
          DurationPlusDuration.apply,
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[DurationPlusDuration, Remote[A]] =
      Schema.Case("DurationPlusDuration", schema, _.asInstanceOf[DurationPlusDuration])
  }

  final case class DurationMinusDuration(left: Remote[Duration], right: Remote[Duration]) extends Remote[Duration] {
    override val schema: SchemaOrNothing.Aux[Duration] = SchemaOrNothing[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        left  <- left.eval[Duration]
        right <- right.eval[Duration]
        result = left.plus(right)
      } yield SchemaAndValue.fromSchemaAndValue(Schema[Duration], result)
  }

  object DurationMinusDuration {
    val schema: Schema[DurationMinusDuration] =
      Schema.defer(
        Schema.CaseClass2[Remote[Duration], Remote[Duration], DurationMinusDuration](
          Schema.Field("left", Remote.schema[Duration]),
          Schema.Field("right", Remote.schema[Duration]),
          DurationMinusDuration.apply,
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[DurationMinusDuration, Remote[A]] =
      Schema.Case("DurationMinusDuration", schema, _.asInstanceOf[DurationMinusDuration])
  }

  final case class Iterate[A](
    initial: Remote[A],
    iterate: EvaluatedRemoteFunction[A, A],
    predicate: EvaluatedRemoteFunction[A, Boolean]
  ) extends Remote[A] {
    override val schema = iterate.schema

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] = {
      def loop(current: Remote[A]): ZIO[RemoteContext, String, SchemaAndValue[A]] =
        predicate(current).eval[Boolean].flatMap {
          case false => current.evalDynamic
          case true  => loop(iterate(current))
        }

      loop(initial)
    }
  }

  object Iterate {
    def schema[A]: Schema[Iterate[A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[A], EvaluatedRemoteFunction[A, A], EvaluatedRemoteFunction[A, Boolean], Iterate[A]](
          Schema.Field("initial", Remote.schema[A]),
          Schema.Field("iterate", EvaluatedRemoteFunction.schema[A, A]),
          Schema.Field("predicate", EvaluatedRemoteFunction.schema[A, Boolean]),
          Iterate.apply,
          _.initial,
          _.iterate,
          _.predicate
        )
      )

    def schemaCase[A]: Schema.Case[Iterate[A], Remote[A]] =
      Schema.Case("Iterate", schema, _.asInstanceOf[Iterate[A]])
  }

  final case class Lazy[A](value: () => Remote[A]) extends Remote[A] {
    override lazy val schema = value().schema

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      value().evalDynamic
  }

  object Lazy {
    def schema[A]: Schema[Lazy[A]] =
      Schema.defer(Remote.schema[A].transform((a: Remote[A]) => Lazy(() => a), _.value()))

    def schemaCase[A]: Schema.Case[Lazy[A], Remote[A]] =
      Schema.Case("Lazy", schema, _.asInstanceOf[Lazy[A]])
  }

  // TODO: This need to be Optional and store the schema in case it's None, like we do with Eithers
  final case class Some0[A](value: Remote[A]) extends Remote[Option[A]] {
    override val schema = SchemaOrNothing.fromSchema(Schema.option(value.schema.schema))

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Option[A]]] =
      for {
        schemaAndValue <- value.evalDynamic
      } yield SchemaAndValue(Schema.option(schemaAndValue.schema), DynamicValue.SomeValue(schemaAndValue.value))
  }

  object Some0 {
    def schema[A]: Schema[Some0[A]] =
      Schema.defer(Remote.schema[A].transform(Some0(_), _.value))

    def schemaCase[A]: Schema.Case[Some0[A], Remote[A]] =
      Schema.Case("Some0", schema, _.asInstanceOf[Some0[A]])
  }

  final case class FoldOption[A, B](
    option: Remote[Option[A]],
    ifEmpty: Remote[B],
    ifNonEmpty: EvaluatedRemoteFunction[A, B]
  ) extends Remote[B] {
    override val schema = ifEmpty.schema

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      option.evalDynamic.flatMap { optionDyn =>
        ZIO.fromEither(optionDyn.toTyped).flatMap { optionTyped =>
          optionTyped.fold(ifEmpty.evalDynamic)((a: A) =>
            ifNonEmpty(Remote.Literal(SchemaAndValue.fromSchemaAndValue(ifNonEmpty.input.schemaA, a))).evalDynamic
          )
        }
      }
  }

  object FoldOption {
    def schema[A, B]: Schema[FoldOption[A, B]] =
      Schema.defer(
        Schema.CaseClass3[Remote[Option[A]], Remote[B], EvaluatedRemoteFunction[A, B], FoldOption[A, B]](
          Schema.Field("option", Remote.schema[Option[A]]),
          Schema.Field("ifEmpty", Remote.schema[B]),
          Schema.Field("ifNonEmpty", EvaluatedRemoteFunction.schema[A, B]),
          FoldOption.apply,
          _.option,
          _.ifEmpty,
          _.ifNonEmpty
        )
      )

    def schemaCase[A]: Schema.Case[FoldOption[Any, A], Remote[A]] =
      Schema.Case("FoldOption", schema, _.asInstanceOf[FoldOption[Any, A]])
  }

  final case class ZipOption[A, B](left: Remote[Option[A]], right: Remote[Option[B]]) extends Remote[Option[(A, B)]] {
    override val schema =
      SchemaOrNothing.fromSchema(
        Schema.option(
          Schema.tuple2(
            left.schema.asInstanceOf[SchemaOrNothing.Aux[Option[A]]].schema.asInstanceOf[Schema.Optional[A]].codec,
            right.schema.asInstanceOf[SchemaOrNothing.Aux[Option[B]]].schema.asInstanceOf[Schema.Optional[B]].codec
          )
        )
      )

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Option[(A, B)]]] =
      for {
        leftDyn  <- left.evalDynamic
        rightDyn <- right.evalDynamic
        leftSchema <- ZIO
                        .attempt(leftDyn.schema.asInstanceOf[Schema.Optional[Any]].codec.asInstanceOf[Schema[A]])
                        .orElseFail("ZipOption.left has unexpected schema")
        rigthSchema <- ZIO
                         .attempt(rightDyn.schema.asInstanceOf[Schema.Optional[Any]].codec.asInstanceOf[Schema[B]])
                         .orElseFail("ZipOption.right has unexpected schema")
        result =
          (leftDyn.value, rightDyn.value) match {
            case (DynamicValue.SomeValue(a), DynamicValue.SomeValue(b)) =>
              SchemaAndValue(
                Schema.option(Schema.tuple2(leftSchema, rigthSchema)),
                DynamicValue.SomeValue(DynamicValue.Tuple(a, b))
              )
            case _ =>
              SchemaAndValue(Schema.option(Schema.tuple2(leftSchema, rigthSchema)), DynamicValue.NoneValue)
          }
      } yield result
  }

  object ZipOption {
    def schema[A, B]: Schema[ZipOption[A, B]] =
      Schema.defer(
        Schema.CaseClass2[Remote[Option[A]], Remote[Option[B]], ZipOption[A, B]](
          Schema.Field("left", Schema.defer(Remote.schema[Option[A]])),
          Schema.Field("right", Schema.defer(Remote.schema[Option[B]])),
          ZipOption.apply[A, B],
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[ZipOption[Any, Any], Remote[A]] =
      Schema.Case("ZipOption", schema[Any, Any], _.asInstanceOf[ZipOption[Any, Any]])
  }

  final case class OptionContains[A](option: Remote[Option[A]], value: Remote[A]) extends Remote[Boolean] {
    override val schema: SchemaOrNothing.Aux[Boolean] = SchemaOrNothing[Boolean]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      option.evalDynamic.flatMap { optionDyn =>
        ZIO
          .attempt(optionDyn.schema.asInstanceOf[Schema.Optional[Any]].codec.asInstanceOf[Schema[A]])
          .orElseFail("OptionContains.option has unexpected schema")
          .flatMap { valueSchema =>
            optionDyn.value match {
              case DynamicValue.SomeValue(dynValue) =>
                ZIO.fromEither(dynValue.toTypedValue(valueSchema)).flatMap { typedValue =>
                  value.eval(valueSchema).map { expectedValue =>
                    SchemaAndValue.fromSchemaAndValue(Schema[Boolean], typedValue == expectedValue)
                  }
                }
              case _ =>
                ZIO.succeed(SchemaAndValue.fromSchemaAndValue(Schema[Boolean], false))
            }
          }
      }
  }

  object OptionContains {
    def schema[A]: Schema[OptionContains[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[Option[A]], Remote[A], OptionContains[A]](
          Schema.Field("left", Schema.defer(Remote.schema[Option[A]])),
          Schema.Field("right", Schema.defer(Remote.schema[A])),
          OptionContains.apply[A],
          _.option,
          _.value
        )
      )

    def schemaCase[A]: Schema.Case[OptionContains[A], Remote[A]] =
      Schema.Case("OptionContains", schema[A], _.asInstanceOf[OptionContains[A]])
  }

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
      .:+:(Cons.schemaCase[A])
      .:+:(UnCons.schemaCase[A])
      .:+:(InstantFromLong.schemaCase[A])
      .:+:(InstantFromLongs.schemaCase[A])
      .:+:(InstantFromMilli.schemaCase[A])
      .:+:(InstantFromString.schemaCase[A])
      .:+:(InstantToTuple.schemaCase[A])
      .:+:(InstantPlusDuration.schemaCase[A])
      .:+:(InstantMinusDuration.schemaCase[A])
      .:+:(InstantTruncate.schemaCase[A])
      .:+:(DurationFromString.schemaCase[A])
      .:+:(DurationBetweenInstants.schemaCase[A])
      .:+:(DurationFromBigDecimal.schemaCase[A])
      .:+:(DurationFromLong.schemaCase[A])
      .:+:(DurationFromLongs.schemaCase[A])
      .:+:(DurationFromAmount.schemaCase[A])
      .:+:(DurationToLongs.schemaCase[A])
      .:+:(DurationToLong.schemaCase[A])
      .:+:(DurationPlusDuration.schemaCase[A])
      .:+:(DurationMinusDuration.schemaCase[A])
      .:+:(Iterate.schemaCase[A])
      .:+:(Lazy.schemaCase[A])
      .:+:(Some0.schemaCase[A])
      .:+:(FoldOption.schemaCase[A])
      .:+:(ZipOption.schemaCase[A])
      .:+:(OptionContains.schemaCase[A])
  )

  implicit val schemaRemoteAny: Schema[Remote[Any]] = schema[Any]
}
