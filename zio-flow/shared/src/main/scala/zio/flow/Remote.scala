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
import zio.flow.remote.numeric._
import zio.flow.remote.RemoteTuples._
import zio.flow.serialization.FlowSchemaAst
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

  def schema: Schema[_ <: A]

  final def iterate[A1 >: A: Schema](
    step: Remote[A1] => Remote[A1]
  )(predicate: Remote[A1] => Remote[Boolean]): Remote[A1] =
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
}

object Remote {
//
//  /**
//   * Constructs accessors that can be used modify remote versions of user
//   * defined data types.
//   */
//  def makeAccessors[A](implicit
//    schema: Schema[A]
//  ): schema.schema.Accessors[RemoteLens, RemotePrism, RemoteTraversal] =
//    schema.schema.makeAccessors(RemoteAccessorBuilder)

  final case class Literal[A](value: DynamicValue, schemaA: Schema[A]) extends Remote[A] {

    override val schema: Schema[A] = schemaA

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

  final case class Flow[R, E, A](flow: ZFlow[R, E, A]) extends Remote[ZFlow[R, E, A]] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[ZFlow[R, E, A]]] =
      ZIO.succeed(SchemaAndValue.fromSchemaAndValue(ZFlow.schema[R, E, A], flow))

    override def schema = ZFlow.schema[R, E, A]
  }

  object Flow {
    def schema[R, E, A]: Schema[Flow[R, E, A]] =
      Schema.defer(
        ZFlow
          .schema[R, E, A]
          .transform(
            Flow(_),
            _.flow
          )
      )

    def schemaCase[A]: Schema.Case[Flow[Any, Any, Any], Remote[A]] =
      Schema.Case("Flow", schema[Any, Any, Any], _.asInstanceOf[Flow[Any, Any, Any]])
  }

  final case class Nested[A](remote: Remote[A]) extends Remote[Remote[A]] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Remote[A]]] =
      ZIO.succeed(SchemaAndValue(Remote.schema[A], DynamicValue.fromSchemaAndValue(Remote.schema[A], remote)))

    override def eval[A1 >: Remote[A]](implicit schema: Schema[A1]): ZIO[RemoteContext, String, A1] =
      ZIO.succeed(remote)

    override def schema = Remote.schema[A]
  }

  object Nested {
    def schema[A]: Schema[Nested[A]] =
      Schema.defer(Remote.schema[A].transform(Nested(_), _.remote))

    def schemaCase[A]: Schema.Case[Nested[Any], Remote[A]] =
      Schema.Case("Nested", schema[Any], _.asInstanceOf[Nested[Any]])
  }

  final case class Ignore() extends Remote[Unit] {
    override val schema: Schema[Unit] = Schema[Unit]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Unit]] =
      ZIO.succeed(SchemaAndValue(Schema.primitive[Unit], DynamicValue.Primitive((), StandardType.UnitType)))
  }
  object Ignore {
    val schema: Schema[Ignore] = Schema[Unit].transform(_ => Ignore(), _ => ())

    def schemaCase[A]: Schema.Case[Ignore, Remote[A]] =
      Schema.Case("Ignore", schema, _.asInstanceOf[Ignore])
  }

  final case class Variable[A](identifier: RemoteVariableName, schemaA: Schema[A]) extends Remote[A] {
    override val schema: Schema[A] = schemaA

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
    def schema[A]: Schema[Variable[A]] =
      Schema.CaseClass2[String, FlowSchemaAst, Variable[A]](
        Schema.Field("identifier", Schema.primitive[String]),
        Schema.Field("schema", FlowSchemaAst.schema),
        construct = (identifier: String, s: FlowSchemaAst) =>
          Variable(RemoteVariableName(identifier), s.toSchema.asInstanceOf[Schema[A]]),
        extractField1 = (variable: Variable[A]) => RemoteVariableName.unwrap(variable.identifier),
        extractField2 = (variable: Variable[A]) => FlowSchemaAst.fromSchema(variable.schemaA)
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
      ApplyEvaluatedFunction(this, a)
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

  final case class RemoteFunction[A: Schema, B](fn: Remote[A] => Remote[B]) extends Remote[B] {
    override lazy val schema = evaluated.result.schema

    lazy val evaluated: EvaluatedRemoteFunction[A, B] = {
      val input = Variable[A](RemoteContext.generateFreshVariableName, Schema[A])
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
      ApplyEvaluatedFunction(evaluated, a)
  }

  type ===>[A, B] = RemoteFunction[A, B]

  final case class ApplyEvaluatedFunction[A, B](f: EvaluatedRemoteFunction[A, B], a: Remote[A]) extends Remote[B] {
    override val schema = f.schema

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[B]] =
      a.evalDynamic.flatMap { value =>
        RemoteContext.setVariable(f.input.identifier, value.value) *>
          f.evalDynamic
      }
  }

  object ApplyEvaluatedFunction {
    def schema[A, B]: Schema[ApplyEvaluatedFunction[A, B]] =
      Schema.CaseClass2[EvaluatedRemoteFunction[A, B], Remote[A], ApplyEvaluatedFunction[A, B]](
        Schema.Field("f", EvaluatedRemoteFunction.schema[A, B]),
        Schema.Field("a", Schema.defer(Remote.schema[A])),
        ApplyEvaluatedFunction.apply,
        _.f,
        _.a
      )

    def schemaCase[A, B]: Schema.Case[ApplyEvaluatedFunction[A, B], Remote[B]] =
      Schema.Case[ApplyEvaluatedFunction[A, B], Remote[B]](
        "RemoteApply",
        schema[A, B],
        _.asInstanceOf[ApplyEvaluatedFunction[A, B]]
      )
  }

  final case class UnaryNumeric[A](
    value: Remote[A],
    numeric: Numeric[A],
    operator: UnaryNumericOperator
  ) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        v <- value.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.unary(operator, v))

    override def schema: Schema[_ <: A] = numeric.schema
  }

  object UnaryNumeric {
    def schema[A]: Schema[UnaryNumeric[A]] =
      Schema.CaseClass3[Remote[A], Numeric[A], UnaryNumericOperator, UnaryNumeric[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        Schema.Field("operator", Schema[UnaryNumericOperator]),
        UnaryNumeric.apply,
        _.value,
        _.numeric,
        _.operator
      )

    def schemaCase[A]: Schema.Case[UnaryNumeric[A], Remote[A]] =
      Schema.Case("UnaryNumeric", schema, _.asInstanceOf[UnaryNumeric[A]])
  }

  final case class BinaryNumeric[A](
    left: Remote[A],
    right: Remote[A],
    numeric: Numeric[A],
    operator: BinaryNumericOperator
  ) extends Remote[A] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        l <- left.eval(numeric.schema)
        r <- right.eval(numeric.schema)
      } yield SchemaAndValue.fromSchemaAndValue(numeric.schema, numeric.binary(operator, l, r))

    override def schema: Schema[_ <: A] = numeric.schema
  }

  object BinaryNumeric {
    def schema[A]: Schema[BinaryNumeric[A]] =
      Schema.CaseClass4[Remote[A], Remote[A], Numeric[A], BinaryNumericOperator, BinaryNumeric[A]](
        Schema.Field("left", Schema.defer(Remote.schema[A])),
        Schema.Field("right", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Numeric.schema.asInstanceOf[Schema[Numeric[A]]]),
        Schema.Field("operator", Schema[BinaryNumericOperator]),
        BinaryNumeric.apply,
        _.left,
        _.right,
        _.numeric,
        _.operator
      )

    def schemaCase[A]: Schema.Case[BinaryNumeric[A], Remote[A]] =
      Schema.Case("BinaryNumeric", schema, _.asInstanceOf[BinaryNumeric[A]])
  }

  final case class UnaryFractional[A](value: Remote[A], fractional: Fractional[A], operator: UnaryFractionalOperator)
      extends Remote[A] {
    override val schema: Schema[A] = fractional.schema

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        v <- value.eval(fractional.schema)
      } yield SchemaAndValue(
        fractional.schema,
        DynamicValue.fromSchemaAndValue(fractional.schema, fractional.unary(operator, v))
      )
  }

  object UnaryFractional {
    def schema[A]: Schema[UnaryFractional[A]] =
      Schema.CaseClass3[Remote[A], Fractional[A], UnaryFractionalOperator, UnaryFractional[A]](
        Schema.Field("value", Schema.defer(Remote.schema[A])),
        Schema.Field("numeric", Fractional.schema.asInstanceOf[Schema[Fractional[A]]]),
        Schema.Field("operator", Schema[UnaryFractionalOperator]),
        UnaryFractional.apply,
        _.value,
        _.fractional,
        _.operator
      )

    def schemaCase[A]: Schema.Case[UnaryFractional[A], Remote[A]] =
      Schema.Case("UnaryFractional", schema, _.asInstanceOf[UnaryFractional[A]])
  }

  final case class RemoteEither[A, B](
    either: Either[(Remote[A], Schema[B]), (Schema[A], Remote[B])]
  ) extends Remote[Either[A, B]] {
    override val schema: Schema[_ <: Either[A, B]] =
      either match {
        case Left((r, s))  => Schema.either(r.schema, s)
        case Right((s, r)) => Schema.either(s, r.schema)
      }

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Either[A, B]]] =
      either match {
        case Left((left, rightSchema)) =>
          left.evalDynamic.flatMap { evaluatedLeft =>
            ZIO.fromEither(evaluatedLeft.toTyped).map { leftValue =>
              SchemaAndValue.fromSchemaAndValue[Either[A, B]](
                Schema.either[A, B](
                  evaluatedLeft.schema.asInstanceOf[Schema[A]],
                  rightSchema
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
                  leftSchema,
                  evaluatedRight.schema.asInstanceOf[Schema[B]]
                ),
                Right(rightValue)
              )
            }
          }
      }

    override def equals(that: Any): Boolean =
      that match {
        case RemoteEither(otherEither) =>
          (either, otherEither) match {
            case (Left((value, schema)), Left((otherValue, otherSchema))) =>
              value == otherValue && Schema.structureEquality.equal(schema, otherSchema)
            case (Right((schema, value)), Right((otherSchema, otherValue))) =>
              value == otherValue && Schema.structureEquality.equal(schema, otherSchema)
            case _ => false
          }
        case _ => false
      }
  }

  object RemoteEither {
    private def leftSchema[A, B]: Schema[(Remote[A], Schema[B])] =
      Schema
        .tuple2(
          Schema.defer(Remote.schema[A]),
          FlowSchemaAst.schema
        )
        .transform(
          { case (v, ast) => (v, ast.toSchema[B]) },
          { case (v, s) => (v, FlowSchemaAst.fromSchema(s)) }
        )

    private def rightSchema[A, B]: Schema[(Schema[A], Remote[B])] =
      Schema
        .tuple2(
          FlowSchemaAst.schema,
          Schema.defer(Remote.schema[B])
        )
        .transform(
          { case (ast, v) => (ast.toSchema[A], v) },
          { case (s, v) => (FlowSchemaAst.fromSchema(s), v) }
        )

    private def eitherSchema[A, B]: Schema[Either[(Remote[A], Schema[B]), (Schema[A], Remote[B])]] =
      Schema.either(leftSchema[A, B], rightSchema[A, B])

    def schema[A, B]: Schema[RemoteEither[A, B]] =
      eitherSchema[A, B].transform(
        RemoteEither.apply,
        _.either
      )

    def schemaCase[A]: Schema.Case[RemoteEither[Any, Any], Remote[A]] =
      Schema.Case("RemoteEither", schema[Any, Any], _.asInstanceOf[RemoteEither[Any, Any]])
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
    override val schema: Schema[Either[B, A]] =
      Schema.either(
        either.schema.asInstanceOf[Schema.EitherSchema[A, B]].right,
        either.schema.asInstanceOf[Schema.EitherSchema[A, B]].left
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

  final case class Try[A](either: Either[(Remote[Throwable], Schema[A]), Remote[A]]) extends Remote[scala.util.Try[A]] {
    override val schema: Schema[scala.util.Try[A]] = {
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

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[util.Try[A]]] =
      either match {
        case Left((remoteThrowable, valueSchema)) =>
          val trySchema = schemaTry(valueSchema)
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
              value == otherValue && Schema.structureEquality.equal(schema, otherSchema)
            case (Right(value), Right(otherValue)) =>
              value == otherValue
            case _ => false
          }
        case _ => false
      }
  }

  object Try {
    private def leftSchema[A]: Schema[(Remote[Throwable], Schema[A])] =
      Schema
        .tuple2(
          Schema.defer(Remote.schema[Throwable]),
          FlowSchemaAst.schema
        )
        .transform(
          { case (v, ast) => (v, ast.toSchema[A]) },
          { case (v, s) => (v, FlowSchemaAst.fromSchema(s)) }
        )

    private def eitherSchema[A]: Schema[Either[(Remote[Throwable], Schema[A]), Remote[A]]] =
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

  // beginning of generated tuple constructors
  final case class Tuple2[T1, T2](t1: Remote[T1], t2: Remote[T2])
      extends Remote[(T1, T2)]
      with RemoteTuple2.Construct[T1, T2]

  object Tuple2 extends RemoteTuple2.ConstructStatic[Tuple2] {
    def construct[T1, T2](t1: Remote[T1], t2: Remote[T2]): Tuple2[T1, T2] = Tuple2(t1, t2)
  }

  final case class Tuple3[T1, T2, T3](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3])
      extends Remote[(T1, T2, T3)]
      with RemoteTuple3.Construct[T1, T2, T3]

  object Tuple3 extends RemoteTuple3.ConstructStatic[Tuple3] {
    def construct[T1, T2, T3](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3]): Tuple3[T1, T2, T3] = Tuple3(t1, t2, t3)
  }

  final case class Tuple4[T1, T2, T3, T4](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3], t4: Remote[T4])
      extends Remote[(T1, T2, T3, T4)]
      with RemoteTuple4.Construct[T1, T2, T3, T4]

  object Tuple4 extends RemoteTuple4.ConstructStatic[Tuple4] {
    def construct[T1, T2, T3, T4](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4]
    ): Tuple4[T1, T2, T3, T4] = Tuple4(t1, t2, t3, t4)
  }

  final case class Tuple5[T1, T2, T3, T4, T5](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5]
  ) extends Remote[(T1, T2, T3, T4, T5)]
      with RemoteTuple5.Construct[T1, T2, T3, T4, T5]

  object Tuple5 extends RemoteTuple5.ConstructStatic[Tuple5] {
    def construct[T1, T2, T3, T4, T5](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5]
    ): Tuple5[T1, T2, T3, T4, T5] = Tuple5(t1, t2, t3, t4, t5)
  }

  final case class Tuple6[T1, T2, T3, T4, T5, T6](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6]
  ) extends Remote[(T1, T2, T3, T4, T5, T6)]
      with RemoteTuple6.Construct[T1, T2, T3, T4, T5, T6]

  object Tuple6 extends RemoteTuple6.ConstructStatic[Tuple6] {
    def construct[T1, T2, T3, T4, T5, T6](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6]
    ): Tuple6[T1, T2, T3, T4, T5, T6] = Tuple6(t1, t2, t3, t4, t5, t6)
  }

  final case class Tuple7[T1, T2, T3, T4, T5, T6, T7](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7)]
      with RemoteTuple7.Construct[T1, T2, T3, T4, T5, T6, T7]

  object Tuple7 extends RemoteTuple7.ConstructStatic[Tuple7] {
    def construct[T1, T2, T3, T4, T5, T6, T7](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7]
    ): Tuple7[T1, T2, T3, T4, T5, T6, T7] = Tuple7(t1, t2, t3, t4, t5, t6, t7)
  }

  final case class Tuple8[T1, T2, T3, T4, T5, T6, T7, T8](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8)]
      with RemoteTuple8.Construct[T1, T2, T3, T4, T5, T6, T7, T8]

  object Tuple8 extends RemoteTuple8.ConstructStatic[Tuple8] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8]
    ): Tuple8[T1, T2, T3, T4, T5, T6, T7, T8] = Tuple8(t1, t2, t3, t4, t5, t6, t7, t8)
  }

  final case class Tuple9[T1, T2, T3, T4, T5, T6, T7, T8, T9](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9)]
      with RemoteTuple9.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9]

  object Tuple9 extends RemoteTuple9.ConstructStatic[Tuple9] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9]
    ): Tuple9[T1, T2, T3, T4, T5, T6, T7, T8, T9] = Tuple9(t1, t2, t3, t4, t5, t6, t7, t8, t9)
  }

  final case class Tuple10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)]
      with RemoteTuple10.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10]

  object Tuple10 extends RemoteTuple10.ConstructStatic[Tuple10] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10]
    ): Tuple10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10] = Tuple10(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)
  }

  final case class Tuple11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)]
      with RemoteTuple11.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11]

  object Tuple11 extends RemoteTuple11.ConstructStatic[Tuple11] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11]
    ): Tuple11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11] = Tuple11(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)
  }

  final case class Tuple12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12)]
      with RemoteTuple12.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12]

  object Tuple12 extends RemoteTuple12.ConstructStatic[Tuple12] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12]
    ): Tuple12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12] =
      Tuple12(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)
  }

  final case class Tuple13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13)]
      with RemoteTuple13.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13]

  object Tuple13 extends RemoteTuple13.ConstructStatic[Tuple13] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13]
    ): Tuple13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13] =
      Tuple13(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13)
  }

  final case class Tuple14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14)]
      with RemoteTuple14.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14]

  object Tuple14 extends RemoteTuple14.ConstructStatic[Tuple14] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14]
    ): Tuple14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14] =
      Tuple14(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14)
  }

  final case class Tuple15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15)]
      with RemoteTuple15.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15]

  object Tuple15 extends RemoteTuple15.ConstructStatic[Tuple15] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15]
    ): Tuple15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15] =
      Tuple15(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15)
  }

  final case class Tuple16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16)]
      with RemoteTuple16.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16]

  object Tuple16 extends RemoteTuple16.ConstructStatic[Tuple16] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16]
    ): Tuple16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16] =
      Tuple16(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16)
  }

  final case class Tuple17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17)]
      with RemoteTuple17.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17]

  object Tuple17 extends RemoteTuple17.ConstructStatic[Tuple17] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17]
    ): Tuple17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17] =
      Tuple17(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17)
  }

  final case class Tuple18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18)]
      with RemoteTuple18.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18]

  object Tuple18 extends RemoteTuple18.ConstructStatic[Tuple18] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18]
    ): Tuple18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18] =
      Tuple18(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18)
  }

  final case class Tuple19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18],
    t19: Remote[T19]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19)]
      with RemoteTuple19.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19]

  object Tuple19 extends RemoteTuple19.ConstructStatic[Tuple19] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18],
      t19: Remote[T19]
    ): Tuple19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19] =
      Tuple19(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19)
  }

  final case class Tuple20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18],
    t19: Remote[T19],
    t20: Remote[T20]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20)]
      with RemoteTuple20.Construct[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15,
        T16,
        T17,
        T18,
        T19,
        T20
      ]

  object Tuple20 extends RemoteTuple20.ConstructStatic[Tuple20] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18],
      t19: Remote[T19],
      t20: Remote[T20]
    ): Tuple20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20] =
      Tuple20(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20)
  }

  final case class Tuple21[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21
  ](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18],
    t19: Remote[T19],
    t20: Remote[T20],
    t21: Remote[T21]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21)]
      with RemoteTuple21.Construct[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15,
        T16,
        T17,
        T18,
        T19,
        T20,
        T21
      ]

  object Tuple21 extends RemoteTuple21.ConstructStatic[Tuple21] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18],
      t19: Remote[T19],
      t20: Remote[T20],
      t21: Remote[T21]
    ): Tuple21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21] =
      Tuple21(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21)
  }

  final case class Tuple22[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21,
    T22
  ](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18],
    t19: Remote[T19],
    t20: Remote[T20],
    t21: Remote[T21],
    t22: Remote[T22]
  ) extends Remote[
        (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22)
      ]
      with RemoteTuple22.Construct[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15,
        T16,
        T17,
        T18,
        T19,
        T20,
        T21,
        T22
      ]

  object Tuple22 extends RemoteTuple22.ConstructStatic[Tuple22] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18],
      t19: Remote[T19],
      t20: Remote[T20],
      t21: Remote[T21],
      t22: Remote[T22]
    ): Tuple22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22] =
      Tuple22(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22)
  }
  // end of generated tuple constructors

  final case class TupleAccess[T, A](tuple: Remote[T], n: Int) extends Remote[A] {
    override val schema: Schema[A] =
      TupleAccess.findSchemaIn(tuple.schema, n).asInstanceOf[Schema[A]]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[A]] =
      for {
        dynTuple <- tuple.evalDynamic
        schema    = TupleAccess.findSchemaIn(dynTuple.schema, n).asInstanceOf[Schema[A]]
        value     = TupleAccess.findValueIn(dynTuple.value, n)
      } yield SchemaAndValue(schema, value)
  }

  object TupleAccess {
    private def findSchemaIn(schema: Schema[_], n: Int): Schema[_] = {
      def find(schema: Schema[_], current: Int): Either[Int, Schema[_]] =
        schema match {
          case Schema.Transform(inner, _, _, _, _) =>
            find(inner, current)
          case Schema.Tuple(a, b, _) =>
            if (current == n) {
              find(a, current)
            } else {
              find(a, current) match {
                case Left(updated) =>
                  find(b, updated)
                case Right(value) =>
                  Right(value)
              }
            }
          case _ if current == n =>
            Right(schema)
          case _ =>
            Left(current + 1)
        }

      find(schema, 0) match {
        case Left(_)      => throw new IllegalStateException(s"Cannot find schema for index $n in tuple schema")
        case Right(value) => value
      }
    }

    private def findValueIn(value: DynamicValue, n: Int): DynamicValue = {
      def find(value: DynamicValue, current: Int): Either[Int, DynamicValue] =
        value match {
          case DynamicValue.Tuple(a, b) =>
            if (current == n) {
              find(a, current)
            } else {
              find(a, current) match {
                case Left(updated) =>
                  find(b, updated)
                case Right(value) =>
                  Right(value)
              }
            }
          case _ if current == n =>
            Right(value)
          case _ =>
            Left(current + 1)
        }

      find(value, 0) match {
        case Left(_)      => throw new IllegalStateException(s"Cannot find value for index $n in dynamic tuple")
        case Right(value) => value
      }
    }

    def schema[T, A]: Schema[TupleAccess[T, A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[T], Int, TupleAccess[T, A]](
          Schema.Field("tuple", Remote.schema[T]),
          Schema.Field("n", Schema[Int]),
          TupleAccess(_, _),
          _.tuple,
          _.n
        )
      )

    def schemaCase[A]: Schema.Case[TupleAccess[Any, A], Remote[A]] =
      Schema.Case("TupleAccess", schema[Any, A], _.asInstanceOf[TupleAccess[Any, A]])
  }

  final case class Branch[A](predicate: Remote[Boolean], ifTrue: Remote[A], ifFalse: Remote[A]) extends Remote[A] {
    override val schema: Schema[A] =
      ifTrue.schema.asInstanceOf[Schema[A]]

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
    override val schema: Schema[Int] = Schema[Int]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Int]] =
      remoteString.eval.map { value =>
        SchemaAndValue.of(value.length)
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
    override val schema: Schema[Boolean] = Schema[Boolean]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      for {
        leftDyn      <- left.evalDynamic
        rightDyn     <- right.evalDynamic
        ordering      = leftDyn.schema.ordering
        leftVal      <- ZIO.fromEither(leftDyn.toTyped)
        rightVal     <- ZIO.fromEither(rightDyn.toTyped)
        compareResult = ordering.compare(leftVal, rightVal.asInstanceOf[leftDyn.Subtype])
      } yield SchemaAndValue.of(compareResult <= 0)
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
    override val schema: Schema[Boolean] = Schema[Boolean]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      for {
        leftDyn  <- left.evalDynamic
        rightDyn <- right.evalDynamic
        result    = leftDyn.value == rightDyn.value && Schema.structureEquality.equal(leftDyn.schema, rightDyn.schema)
      } yield SchemaAndValue.of(result)
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
    override val schema: Schema[Boolean] = Schema[Boolean]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      value.eval.map { boolValue =>
        SchemaAndValue.of(!boolValue)
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
    override val schema: Schema[Boolean] = Schema[Boolean]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Boolean]] =
      for {
        lval <- left.eval
        rval <- right.eval
      } yield SchemaAndValue.of(lval && rval)
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

    override val schema: Schema[List[A]] = list.schema.asInstanceOf[Schema[List[A]]]

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

    override val schema: Schema[Option[(A, List[A])]] = {
      val listSchema = list.schema.asInstanceOf[Schema.Sequence[List[A], A, _]]
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

  final case class InstantFromLongs(seconds: Remote[Long], nanos: Remote[Long]) extends Remote[Instant] {
    override val schema: Schema[Instant] = Schema[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      for {
        s <- seconds.eval[Long]
        n <- nanos.eval[Long]
      } yield SchemaAndValue.of(Instant.ofEpochSecond(s, n))
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

  final case class InstantFromString(charSeq: Remote[String]) extends Remote[Instant] {
    override val schema: Schema[Instant] = Schema[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      charSeq.eval[String].map(s => SchemaAndValue.of(Instant.parse(s)))
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
    override val schema: Schema[(Long, Int)] = Schema[(Long, Int)]

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
    override val schema: Schema[Instant] = Schema[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      for {
        instant  <- instant.eval[Instant]
        duration <- duration.eval[Duration]
        result    = instant.plusSeconds(duration.getSeconds).plusNanos(duration.getNano.toLong)
      } yield SchemaAndValue.of(result)
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

  final case class InstantTruncate(instant: Remote[Instant], temporalUnit: Remote[ChronoUnit]) extends Remote[Instant] {
    override val schema: Schema[Instant] = Schema[Instant]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Instant]] =
      for {
        instant      <- instant.eval[Instant]
        temporalUnit <- temporalUnit.eval[ChronoUnit].tapError(s => ZIO.debug(s"Failed to evaluate temporal unit: $s"))
        result        = instant.truncatedTo(temporalUnit)
      } yield SchemaAndValue.of(result)
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
    override val schema: Schema[Duration] = Schema[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      charSeq.eval[String].map(s => SchemaAndValue.of(Duration.parse(s)))
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
    override val schema: Schema[Duration] = Schema[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        start <- startInclusive.eval[Instant]
        end   <- endExclusive.eval[Instant]
        result = Duration.between(start, end)
      } yield SchemaAndValue.of(result)
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

    override val schema: Schema[Duration] = Schema[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        bd     <- seconds.eval[BigDecimal]
        seconds = bd.longValue()
        nanos   = bd.subtract(new BigDecimal(seconds)).multiply(DurationFromBigDecimal.oneBillion).intValue()
        result  = Duration.ofSeconds(seconds, nanos.toLong)
      } yield SchemaAndValue.of(result)
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

  final case class DurationFromLongs(seconds: Remote[Long], nanoAdjustment: Remote[Long]) extends Remote[Duration] {
    override val schema: Schema[Duration] = Schema[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        seconds        <- seconds.eval[Long]
        nanoAdjustment <- nanoAdjustment.eval[Long]
        result          = Duration.ofSeconds(seconds, nanoAdjustment)
      } yield SchemaAndValue.of(result)
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
    override val schema: Schema[Duration] = Schema[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        amount       <- amount.eval[Long]
        temporalUnit <- temporalUnit.eval[ChronoUnit]
        result        = Duration.of(amount, temporalUnit)
      } yield SchemaAndValue.of(result)
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
    override val schema: Schema[(Long, Long)] = Schema[(Long, Long)]

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

  final case class DurationPlusDuration(left: Remote[Duration], right: Remote[Duration]) extends Remote[Duration] {
    override val schema: Schema[Duration] = Schema[Duration]

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        left  <- left.eval[Duration]
        right <- right.eval[Duration]
        result = left.plus(right)
      } yield SchemaAndValue.of(result)
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

  final case class DurationMultipliedBy(left: Remote[Duration], right: Remote[Long]) extends Remote[Duration] {
    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Duration]] =
      for {
        left  <- left.eval[Duration]
        right <- right.eval[Long]
        result = left.multipliedBy(right)
      } yield SchemaAndValue.of(result)

    override def schema: Schema[_ <: Duration] = Schema[Duration]
  }

  object DurationMultipliedBy {
    val schema: Schema[DurationMultipliedBy] =
      Schema.defer(
        Schema.CaseClass2[Remote[Duration], Remote[Long], DurationMultipliedBy](
          Schema.Field("left", Remote.schema[Duration]),
          Schema.Field("right", Remote.schema[Long]),
          DurationMultipliedBy.apply,
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[DurationMultipliedBy, Remote[A]] =
      Schema.Case("DurationMultipliedBy", schema, _.asInstanceOf[DurationMultipliedBy])
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

  final case class AsString[A, A1 >: A: Schema](initial: ZIO[RemoteContext, String, A1])(f: A1 => SchemaAndValue[String]) extends Remote[String] {
    override val schema =
      Schema.Primitive(StandardType.StringType)

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[String]] =
      initial.map(f)
  }

  def asRawString[A, A1 >: A: Schema](initial: ZIO[RemoteContext, String, A1])(f: A1 => String): Remote[String] =
    AsString(initial)(a => SchemaAndValue.of(f(a)))

  def instantStringRemote[A, A1 >: A: Schema](rem: Remote[A1]): Remote[String] =
    instantString[A, A1](remoteToString)(rem)

  def instantString[A, A1 >: A: Schema](els: Remote[A1] => Remote[String])(rem: Remote[A1]): Remote[String] =
    rem match {
      case InstantFromLongs(s, n) =>
        asRawString(s.eval[Long] zip n.eval[Long]) {
          case (ss, nn) =>
            "(" + ss.toString + " seconds," + nn.toString + " nanoseconds)"
        }
      case InstantFromString(s) =>
        asRawString(s.eval[String])(identity)
      case InstantToTuple(i) =>
        asRawString(i.eval[Instant])(i => {
          "(" + i.getEpochSecond.toString + " epoch seconds," + i.getNano.toString + " nanoseconds)"
        })
      case InstantPlusDuration(i, d) =>
        asRawString(i.eval[Instant] zip d.eval[Duration]) {
          case (ii, dd) => {
            val n = ii.plusSeconds(dd.getSeconds).plusNanos(dd.getNano.toLong)
            "(" + n.getEpochSecond.toString + " epoch seconds," + n.getNano.toString + " nanoseconds)"
          }
        }
      case InstantTruncate(i, t) =>
        asRawString(i.eval[Instant] zip t.eval[ChronoUnit]) {
          case (ii, tt) => {
            val n = ii.truncatedTo(tt)
            "(" + n.getEpochSecond.toString + " epoch seconds," + n.getNano.toString + " nanoseconds)"
          }
        }
      case _ =>
        els(rem)
    }

  def durationStringRemote[A, A1 >: A: Schema](rem: Remote[A1]): Remote[String] =
    durationString[A, A1](remoteToString)(rem)

  def durationString[A, A1 >: A: Schema](els: Remote[A1] => Remote[String])(rem: Remote[A1]): Remote[String] =
    rem match {
      case DurationFromString(s) =>
        asRawString(s.eval[String])(identity)
      case DurationBetweenInstants(s, e) =>
        asRawString(s.eval[Instant] zip e.eval[Instant]) {
          case (ss, ee) =>
            "(" + ss.getEpochSecond.toString + " epoch seconds," + ss.getNano.toString + " nanoseconds)" +
            " <-> " +
            "(" + ee.getEpochSecond.toString + " epoch seconds," + ee.getNano.toString + " nanoseconds)"
        }
      case DurationFromBigDecimal(s) =>
        asRawString(s.eval[BigDecimal])(_.toString + " seconds")
      case DurationFromLongs(s, n) =>
        asRawString(s.eval[Long] zip n.eval[Long]) {
          case (ss, nn) =>
            "(" + ss.toString + " seconds," + nn.toString + " nanosecond adjustment)"
        }
      case DurationFromAmount(a, t) =>
        asRawString(a.eval[Long] zip t.eval[ChronoUnit]) {
          case (aa, tt) =>
            "(" + aa.toString + " amount," + tt.toString + " temporal unit)"
        }
      case DurationToLongs(d) =>
        asRawString(d.eval[Duration])(d => "duration " + d.toString)
      case DurationPlusDuration(l, r) =>
        asRawString(l.eval[Duration] zip r.eval[Duration]) {
          case (ll, rr) =>
            "DurationPlusDuration(" + ll.toString + "," + rr.toString + ")"
        }
      case DurationMultipliedBy(l, r) =>
        asRawString(l.eval[Duration] zip r.eval[Long]) {
          case (ll, rr) =>
            "DurationMultipliedBy(" + ll.toString + "," + rr.toString + ")"
        }
      case _ =>
        els(rem)
    }

  def fractionalStringRemote[A, A1 >: A: Schema](rem: Remote[A1]): Remote[String] =
    fractionalString[A, A1](remoteToString)(rem)

  def fractionalString[A, A1 >: A: Schema](els: Remote[A1] => Remote[String])(rem: Remote[A1]): Remote[String] =
    rem match {
      case UnaryFractional(v, _, o) =>
        asRawString(v.eval[A1])(a => o.show(a.toString))
      case _ =>
        els(rem)
    }

  def numericStringRemote[A, A1 >: A: Schema](rem: Remote[A1]): Remote[String] =
    numericString[A, A1](remoteToString)(rem)

  def numericString[A, A1 >: A: Schema](els: Remote[A1] => Remote[String])(rem: Remote[A1]): Remote[String] =
    rem match {
      case UnaryNumeric(v, _, o) =>
        asRawString(v.eval[A1])(a => o.show(a.toString))
      case BinaryNumeric(l, r, _, o) =>
        asRawString(l.eval[A1] zip r.eval[A1]) {
          case (a1, a2) =>
            o.show(a1.toString, a2.toString)
        }
      case _ =>
        els(rem)
    }

  def remoteToString[A, A1 >: A: Schema](rem: Remote[A1]): Remote[String] =
    asRawString(rem.eval[A1])(_.toString)

  final case class RemoteSome[A](value: Remote[A]) extends Remote[Option[A]] {
    override val schema = Schema.option(value.schema)

    override def evalDynamic: ZIO[RemoteContext, String, SchemaAndValue[Option[A]]] =
      for {
        schemaAndValue <- value.evalDynamic
      } yield SchemaAndValue(Schema.option(schemaAndValue.schema), DynamicValue.SomeValue(schemaAndValue.value))
  }

  object RemoteSome {
    def schema[A]: Schema[RemoteSome[A]] =
      Schema.defer(Remote.schema[A].transform(RemoteSome(_), _.value))

    def schemaCase[A]: Schema.Case[RemoteSome[A], Remote[A]] =
      Schema.Case("RemoteSome", schema, _.asInstanceOf[RemoteSome[A]])
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

//  final case class LensGet[S, A](whole: Remote[S], lens: RemoteLens[S, A]) extends Remote[A] {
//    val schema: Schema[A] = SchemaOrNothing.fromSchema(lens.schemaPiece)
//
//    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
//      whole.evalWithSchema.map {
//        case Right(SchemaAndValue(_, whole)) => Right(SchemaAndValue(lens.schemaPiece, lens.unsafeGet(whole)))
//        case _                               => Left(self)
//      }
//  }
//
//  final case class LensSet[S, A](whole: Remote[S], piece: Remote[A], lens: RemoteLens[S, A]) extends Remote[S] {
//    val schema: Schema[S] = SchemaOrNothing.fromSchema(lens.schemaWhole)
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
//    val schema: Schema[Option[A]] = SchemaOrNothing.fromSchema(Schema.option(prism.schemaPiece))
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
//    val schema: Schema[S] = SchemaOrNothing.fromSchema(prism.schemaWhole)
//
//    def evalWithSchema: ZIO[RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
//      piece.evalWithSchema.map {
//        case Right(SchemaAndValue(_, piece)) => Right(SchemaAndValue(prism.schemaWhole, prism.unsafeSet(piece)))
//        case _                               => Left(self)
//      }
//  }
//
//  final case class TraversalGet[S, A](whole: Remote[S], traversal: RemoteTraversal[S, A]) extends Remote[Chunk[A]] {
//    val schema: Schema[Chunk[A]] = SchemaOrNothing.fromSchema(Schema.chunk(traversal.schemaPiece))
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
//    val schema: Schema[S] = SchemaOrNothing.fromSchema(traversal.schemaWhole)
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
    // TODO: do this on type level instead
    value match {
      case dynamicValue: DynamicValue =>
        Literal(dynamicValue, Schema[A])
      case flow: ZFlow[_, _, _] =>
        Flow(flow).asInstanceOf[Remote[A]]
      case remote: Remote[Any] =>
        Nested(remote).asInstanceOf[Remote[A]]
      case _ =>
        Literal(DynamicValue.fromSchemaAndValue(Schema[A], value), Schema[A])
    }

  def sequenceEither[A, B](
    either: Either[Remote[A], Remote[B]]
  )(implicit aSchema: Schema[A], bSchema: Schema[B]): Remote[Either[A, B]] =
    RemoteEither(either match {
      case Left(l)  => Left((l, bSchema))
      case Right(r) => Right((aSchema, r))
    })

  def ofEpochSecond(second: Remote[Long]): Remote[Instant] = Remote.InstantFromLongs(second, Remote(0L))

  def ofEpochSecond(second: Remote[Long], nanos: Remote[Long]): Remote[Instant] = Remote.InstantFromLongs(second, nanos)

  def ofEpochMilli(milliSecond: Remote[Long]): Remote[Instant] =
    Remote.InstantFromLongs(
      milliSecond / 1000L,
      (milliSecond % 1000L) * 1000000L
    )

  def ofSeconds(seconds: Remote[Long]): Remote[Duration] = Remote.DurationFromLongs(seconds, Remote(0L))

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

  implicit def capturedRemoteToRemote[A: Schema, B](f: Remote[A] => Remote[B]): RemoteFunction[A, B] =
    RemoteFunction((a: Remote[A]) => f(a))

  val unit: Remote[Unit] = Remote.Ignore()

  private def createSchema[A]: Schema[Remote[A]] = Schema.EnumN(
    CaseSet
      .Cons(Literal.schemaCase[A], CaseSet.Empty[Remote[A]]())
      .:+:(Flow.schemaCase[A])
      .:+:(Nested.schemaCase[A])
      .:+:(Ignore.schemaCase[A])
      .:+:(Variable.schemaCase[A])
      .:+:(UnaryNumeric.schemaCase[A])
      .:+:(BinaryNumeric.schemaCase[A])
      .:+:(UnaryFractional.schemaCase[A])
      .:+:(EvaluatedRemoteFunction.schemaCase[Any, A])
      .:+:(ApplyEvaluatedFunction.schemaCase[Any, A])
      .:+:(RemoteEither.schemaCase[A])
      .:+:(FoldEither.schemaCase[Any, Any, A])
      .:+:(SwapEither.schemaCase[A])
      .:+:(Try.schemaCase[A])
      .:+:(Tuple2.schemaCase[A])
      .:+:(Tuple3.schemaCase[A])
      .:+:(Tuple4.schemaCase[A])
      .:+:(Tuple5.schemaCase[A])
      .:+:(Tuple6.schemaCase[A])
      .:+:(Tuple7.schemaCase[A])
      .:+:(Tuple8.schemaCase[A])
      .:+:(Tuple9.schemaCase[A])
      .:+:(Tuple10.schemaCase[A])
      .:+:(Tuple11.schemaCase[A])
      .:+:(Tuple12.schemaCase[A])
      .:+:(Tuple13.schemaCase[A])
      .:+:(Tuple14.schemaCase[A])
      .:+:(Tuple15.schemaCase[A])
      .:+:(Tuple16.schemaCase[A])
      .:+:(Tuple17.schemaCase[A])
      .:+:(Tuple18.schemaCase[A])
      .:+:(Tuple19.schemaCase[A])
      .:+:(Tuple20.schemaCase[A])
      .:+:(Tuple21.schemaCase[A])
      .:+:(Tuple22.schemaCase[A])
      .:+:(TupleAccess.schemaCase[A])
      .:+:(Branch.schemaCase[A])
      .:+:(Length.schemaCase[A])
      .:+:(LessThanEqual.schemaCase[A])
      .:+:(Equal.schemaCase[A])
      .:+:(Not.schemaCase[A])
      .:+:(And.schemaCase[A])
      .:+:(Fold.schemaCase[A])
      .:+:(Cons.schemaCase[A])
      .:+:(UnCons.schemaCase[A])
      .:+:(InstantFromLongs.schemaCase[A])
      .:+:(InstantFromString.schemaCase[A])
      .:+:(InstantToTuple.schemaCase[A])
      .:+:(InstantPlusDuration.schemaCase[A])
      .:+:(InstantTruncate.schemaCase[A])
      .:+:(DurationFromString.schemaCase[A])
      .:+:(DurationBetweenInstants.schemaCase[A])
      .:+:(DurationFromBigDecimal.schemaCase[A])
      .:+:(DurationFromLongs.schemaCase[A])
      .:+:(DurationFromAmount.schemaCase[A])
      .:+:(DurationToLongs.schemaCase[A])
      .:+:(DurationPlusDuration.schemaCase[A])
      .:+:(DurationMultipliedBy.schemaCase[A])
      .:+:(Iterate.schemaCase[A])
      .:+:(Lazy.schemaCase[A])
      .:+:(RemoteSome.schemaCase[A])
      .:+:(FoldOption.schemaCase[A])
  )

  implicit val schemaAny: Schema[Remote[Any]] = createSchema[Any]
  def schema[A]: Schema[Remote[A]]            = schemaAny.asInstanceOf[Schema[Remote[A]]]
}
