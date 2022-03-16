package zio.flow.serialization

import zio.{Random, flow}
import zio.flow.{Remote, RemoteVariableName, SchemaAndValue, SchemaOrNothing}
import zio.flow.remote.{Fractional, Numeric}
import zio.flow.serialization.Generators.TestException
import zio.schema.{DefaultJavaTimeSchemas, Schema}
import zio.test.{Gen, Sized}

trait Generators extends DefaultJavaTimeSchemas {

  val genPrimitiveSchemaAndValue: Gen[Random with Sized, SchemaAndValue[Any]] =
    Gen.oneOf(
      Gen.string.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[String], value)),
      Gen.int.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)),
      Gen.double.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[Double], value)),
      Gen.instant.map(value => SchemaAndValue.fromSchemaAndValue(instantSchema, value))
    )

  val genRemoteVariableName: Gen[Random with Sized, flow.RemoteVariableName.Type] =
    Gen.string1(Gen.alphaNumericChar).map(RemoteVariableName.apply(_))

  val genNumeric: Gen[Random, (Numeric[Any], Gen[Random, Remote[Any]])] =
    Gen.oneOf(
      Gen
        .const(Numeric.NumericShort)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.short.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Short], value)))
          )
        ),
      Gen
        .const(Numeric.NumericInt)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.int.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)))
          )
        ),
      Gen
        .const(Numeric.NumericLong)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.long.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Long], value)))
          )
        ),
      Gen
        .const(Numeric.NumericFloat)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.float.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Float], value)))
          )
        ),
      Gen
        .const(Numeric.NumericDouble)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.double.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Double], value)))
          )
        ),
      Gen
        .const(Numeric.NumericBigInt)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen
              .bigInt(BigInt(-1000), BigInt(1000))
              .map(value =>
                Remote
                  .Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[java.math.BigInteger], value.bigInteger))
              )
          )
        ),
      Gen
        .const(Numeric.NumericBigDecimal)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen
              .bigDecimal(BigDecimal(-1000), BigDecimal(1000))
              .map(value =>
                Remote
                  .Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[java.math.BigDecimal], value.bigDecimal))
              )
          )
        )
    )

  val genFractional: Gen[Random, (Fractional[Any], Gen[Random, Remote[Any]])] =
    Gen.oneOf(
      Gen
        .const(Fractional.FractionalFloat)
        .map(n =>
          (
            n.asInstanceOf[Fractional[Any]],
            Gen.float.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Float], value)))
          )
        ),
      Gen
        .const(Fractional.FractionalDouble)
        .map(n =>
          (
            n.asInstanceOf[Fractional[Any]],
            Gen.double.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Double], value)))
          )
        ),
      Gen
        .const(Fractional.FractionalBigDecimal)
        .map(n =>
          (
            n.asInstanceOf[Fractional[Any]],
            Gen
              .bigDecimal(BigDecimal(-1000), BigDecimal(1000))
              .map(value =>
                Remote
                  .Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[java.math.BigDecimal], value.bigDecimal))
              )
          )
        )
    )

  val genThrowableSchemaAndValue: Gen[Random with Sized, SchemaAndValue[Throwable]] =
    Gen.alphaNumericString.map(msg =>
      SchemaAndValue.fromSchemaAndValue(
        SchemaOrNothing.fromSchema(zio.flow.schemaThrowable).schema,
        new TestException(msg)
      )
    )

  val genLiteral: Gen[Random with Sized, Remote[Any]] =
    Gen.oneOf(genPrimitiveSchemaAndValue).map(Remote.Literal(_))

  val genRemoteVariable: Gen[Random with Sized, Remote[Any]] =
    for {
      name           <- genRemoteVariableName
      schemaAndValue <- genPrimitiveSchemaAndValue
    } yield Remote.Variable(name, schemaAndValue.schema)

  val genAddNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.AddNumeric(left, right, numeric)

  val genDivNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.DivNumeric(left, right, numeric)

  val genMulNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.MulNumeric(left, right, numeric)

  val genPowNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.PowNumeric(left, right, numeric)

  val genNegationNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.NegationNumeric(value, numeric)

  val genRootNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      n             <- gen
    } yield Remote.RootNumeric(value, n, numeric)

  val genLogNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      base          <- gen
    } yield Remote.LogNumeric(value, base, numeric)

  val genModNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      left  <- Gen.int.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)))
      right <- Gen.int.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)))
    } yield Remote.ModNumeric(left, right)

  val genAbsoluteNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.AbsoluteNumeric(value, numeric)

  val genFloorNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.FloorNumeric(value, numeric)

  val genCeilNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.CeilNumeric(value, numeric)

  val genRoundNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.RoundNumeric(value, numeric)

  val genMinNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      base          <- gen
    } yield Remote.MinNumeric(value, base, numeric)

  val genMaxNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      base          <- gen
    } yield Remote.MaxNumeric(value, base, numeric)

  val genSinFractional: Gen[Random with Sized, Remote[Any]] =
    for {
      pair             <- genFractional
      (fractional, gen) = pair
      value            <- gen
    } yield Remote.SinFractional(value, fractional)

  val genSinInverseFractional: Gen[Random with Sized, Remote[Any]] =
    for {
      pair             <- genFractional
      (fractional, gen) = pair
      value            <- gen
    } yield Remote.SinInverseFractional(value, fractional)

  val genTanInverseFractional: Gen[Random with Sized, Remote[Any]] =
    for {
      pair             <- genFractional
      (fractional, gen) = pair
      value            <- gen
    } yield Remote.TanInverseFractional(value, fractional)

  val genEvaluatedRemoteFunction: Gen[Random with Sized, Remote[Any]] =
    for {
      v <- genRemoteVariable
      r <- Gen.oneOf(genLiteral, genRemoteVariable, genAddNumeric)
    } yield Remote.EvaluatedRemoteFunction(v.asInstanceOf[Remote.Variable[Any]], r)

  val genEvaluatedRemoteFunctionWithResultSchema: Gen[Random with Sized, (Remote[Any], Schema[Any])] =
    for {
      v <- genRemoteVariable
      (r, s) <- Gen.oneOf(
                  genLiteral.map(l => (l, l.asInstanceOf[Remote.Literal[Any]].schema)),
                  genRemoteVariable.map(l => (l, l.asInstanceOf[Remote.Variable[Any]].schema)),
                  genAddNumeric.map(l => (l, l.asInstanceOf[Remote.AddNumeric[Any]].numeric.schema))
                )
    } yield (Remote.EvaluatedRemoteFunction(v.asInstanceOf[Remote.Variable[Any]], r), s)

  val genRemoteApply: Gen[Random with Sized, Remote[Any]] =
    for {
      f <- genEvaluatedRemoteFunction
      a <- genLiteral
    } yield Remote.RemoteApply(f.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Any]], a)

  val genEither0: Gen[Random with Sized, Remote[Any]] =
    for {
      left  <- genPrimitiveSchemaAndValue
      right <- genPrimitiveSchemaAndValue
      either <- Gen.oneOf(
                  Gen.const(Left((left.toRemote, SchemaOrNothing.fromSchema(right.schema.asInstanceOf[Schema[Any]])))),
                  Gen.const(Right((SchemaOrNothing.fromSchema(left.schema.asInstanceOf[Schema[Any]]), right.toRemote)))
                )
    } yield Remote.Either0(either)

  val genFlatMapEither: Gen[Random with Sized, Remote[Any]] =
    for {
      left  <- genPrimitiveSchemaAndValue
      right <- genPrimitiveSchemaAndValue
      either <- Gen.oneOf(
                  Gen.const(Left((left.toRemote, SchemaOrNothing.fromSchema(right.schema.asInstanceOf[Schema[Any]])))),
                  Gen.const(Right((SchemaOrNothing.fromSchema(left.schema.asInstanceOf[Schema[Any]]), right.toRemote)))
                )
      (f, cSchema) <- genEvaluatedRemoteFunctionWithResultSchema
      aSchema       = SchemaOrNothing.fromSchema(left.schema.asInstanceOf[Schema[Any]])
    } yield Remote.FlatMapEither[Any, Any, Any](
      Remote.Either0(either),
      f.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Either[Any, Any]]],
      aSchema,
      SchemaOrNothing.fromSchema(cSchema)
    )

  val genFoldEither: Gen[Random with Sized, Remote[Any]] =
    for {
      left  <- genPrimitiveSchemaAndValue
      right <- genPrimitiveSchemaAndValue
      either <- Gen.oneOf(
                  Gen.const(Left((left.toRemote, SchemaOrNothing.fromSchema(right.schema.asInstanceOf[Schema[Any]])))),
                  Gen.const(Right((SchemaOrNothing.fromSchema(left.schema.asInstanceOf[Schema[Any]]), right.toRemote)))
                )
      // TODO: generate functions compatible with the generated either
      leftF  <- genEvaluatedRemoteFunction.map(_.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Either[Any, Any]]])
      rightF <- genEvaluatedRemoteFunction.map(_.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Either[Any, Any]]])
    } yield Remote.FoldEither(Remote.Either0(either), leftF, rightF)

  val genSwapEither: Gen[Random with Sized, Remote[Any]] =
    genEither0.map(r => Remote.SwapEither(r.asInstanceOf[Remote[Either[Any, Any]]]))

  val genTry: Gen[Random with Sized, Remote[Any]] =
    for {
      value <- genPrimitiveSchemaAndValue
      error <- genThrowableSchemaAndValue
      either <- Gen.either(
                  Gen.const(
                    (
                      error.toRemote,
                      SchemaOrNothing.fromSchema(value.schema.asInstanceOf[Schema[Any]])
                    )
                  ),
                  Gen.const(value.toRemote)
                )
    } yield Remote.Try(either)
}

object Generators {
  final class TestException(message: String) extends RuntimeException(message) {
    // Weak equality test for roundtrip exception serialization
    override def equals(obj: Any): Boolean =
      obj match {
        case t: Throwable => t.getMessage == message
        case _            => false
      }
  }
}
