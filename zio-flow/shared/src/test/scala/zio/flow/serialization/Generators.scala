package zio.flow.serialization

import zio.{Random, flow}
import zio.flow.{Remote, RemoteVariableName, SchemaAndValue, SchemaOrNothing}
import zio.flow.remote.Numeric
import zio.schema.{DefaultJavaTimeSchemas, Schema}
import zio.test.{Gen, Sized}

trait Generators extends DefaultJavaTimeSchemas {

  val genPrimitiveSchemaAndValue: Gen[Random with Sized, SchemaAndValue[Any]] =
    Gen.oneOf(
      Gen.string.map(value => SchemaAndValue(Schema.primitive[String], value)),
      Gen.int.map(value => SchemaAndValue(Schema.primitive[Int], value)),
      Gen.double.map(value => SchemaAndValue(Schema.primitive[Double], value)),
      Gen.instant.map(value => SchemaAndValue(instantSchema, value))
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
            Gen.short.map(value => Remote.Literal(SchemaAndValue(Schema.primitive[Short], value)))
          )
        ),
      Gen
        .const(Numeric.NumericInt)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.int.map(value => Remote.Literal(SchemaAndValue(Schema.primitive[Int], value)))
          )
        ),
      Gen
        .const(Numeric.NumericLong)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.long.map(value => Remote.Literal(SchemaAndValue(Schema.primitive[Long], value)))
          )
        ),
      Gen
        .const(Numeric.NumericFloat)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.float.map(value => Remote.Literal(SchemaAndValue(Schema.primitive[Float], value)))
          )
        ),
      Gen
        .const(Numeric.NumericDouble)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.double.map(value => Remote.Literal(SchemaAndValue(Schema.primitive[Double], value)))
          )
        ),
      Gen
        .const(Numeric.NumericBigInt)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen
              .bigInt(BigInt(-1000), BigInt(1000))
              .map(value => Remote.Literal(SchemaAndValue(Schema.primitive[java.math.BigInteger], value.bigInteger)))
          )
        ),
      Gen
        .const(Numeric.NumericBigDecimal)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen
              .bigDecimal(BigDecimal(-1000), BigDecimal(1000))
              .map(value => Remote.Literal(SchemaAndValue(Schema.primitive[java.math.BigDecimal], value.bigDecimal)))
          )
        )
    )

  val genLiteral: Gen[Random with Sized, Remote[Any]] = genPrimitiveSchemaAndValue.map(Remote.Literal(_))
  val genRemoteVariable: Gen[Random with Sized, Remote[Any]] =
    for {
      name           <- genRemoteVariableName
      schemaAndValue <- genPrimitiveSchemaAndValue
    } yield Remote.Variable(name, SchemaOrNothing.fromSchema(schemaAndValue.schema))

  val genAddNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.AddNumeric(left, right, numeric)
}
