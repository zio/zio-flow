package zio.flow.serialization

import zio.Random
import zio.flow.{Remote, SchemaAndValue}
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

  val genLiteral: Gen[Random with Sized, Remote[Any]] = genPrimitiveSchemaAndValue.map(Remote.Literal(_))
}
