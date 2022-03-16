package zio.flow.remote

import zio.flow.{Remote, RemoteContext, RemoteVariableName, SchemaAndValue, SchemaOrNothing}
import zio.schema.{DynamicValue, Schema, StandardType}
import zio.test.Assertion._
import zio.test._

object RemoteSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Remote")(
      suite("Literal")(
        test("evaluates correctly") {
          val remote = Remote("test")
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[String]
            } yield assertTrue(
              dyn == SchemaAndValue(Schema.primitive[String], DynamicValue.Primitive("test", StandardType[String])),
              typ == "test"
            )

          test.provide(RemoteContext.inMemory)
        }
      ),
      suite("Variable")(
        test("evaluates to the stored value") {
          val name   = RemoteVariableName("test")
          val remote = Remote.Variable(name, Schema.primitive[String])
          val test =
            for {
              _   <- RemoteContext.setVariable(name, DynamicValue.fromSchemaAndValue(Schema[String], "test"))
              dyn <- remote.evalDynamic
              typ <- remote.eval[String]
            } yield assertTrue(
              dyn == SchemaAndValue(Schema.primitive[String], DynamicValue.Primitive("test", StandardType[String])),
              typ == "test"
            )

          test.provide(RemoteContext.inMemory)
        },
        test("fails if not stored") {
          val name   = RemoteVariableName("test")
          val remote = Remote.Variable(name, Schema.primitive[Int])
          val test =
            for {
              dyn <- remote.evalDynamic.exit
            } yield assert(dyn)(fails(equalTo("Could not find identifier test")))

          test.provide(RemoteContext.inMemory)
        }
      ),
      suite("Either")(
        test("evaluates correctly when it is Left") {
          val remote = Remote.Either0(Left((Remote("test"), SchemaOrNothing.fromSchema(Schema[Int]))))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[String, Int]]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema.either[String, Int], Left("test")),
              typ == Left("test")
            )

          test.provide(RemoteContext.inMemory)
        },
        test("evaluates correctly when it is Right") {
          val remote = Remote.Either0(Right((SchemaOrNothing.fromSchema(Schema[Int]), (Remote("test")))))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[Int, String]]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema.either[Int, String], Right("test")),
              typ == Right("test")
            )

          test.provide(RemoteContext.inMemory)
        }
      ),
      suite("FlatMapEither")(
        test("evaluates correctly when it is Left") {
          val remote = Remote.FlatMapEither(
            Remote.Either0(Left((Remote("test"), SchemaOrNothing.fromSchema[Int]))),
            Remote.RemoteFunction((a: Remote[Int]) =>
              Remote.Either0(Right((SchemaOrNothing.fromSchema[String], Remote.AddNumeric(a, Remote(1), Numeric.NumericInt))))).evaluated,
            SchemaOrNothing.fromSchema[String],
            SchemaOrNothing.fromSchema[Int]
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[String, Int]]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema.either[String, Int], Left("test")),
              typ == Left("test")
            )
          test.provide(RemoteContext.inMemory)
        },
        test("evaluates correctly when it is Right") {
          val remote = Remote.FlatMapEither(
            Remote.Either0(Right((SchemaOrNothing.fromSchema[String], Remote(10)))),
            Remote.RemoteFunction((a: Remote[Int]) =>
              Remote.Either0(Right((SchemaOrNothing.fromSchema[String], Remote.AddNumeric(a, Remote(1), Numeric.NumericInt))))).evaluated,
            SchemaOrNothing.fromSchema[String],
            SchemaOrNothing.fromSchema[Int]
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[String, Int]]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema.either[String, Int], Right(11)),
              typ == Right(11)
            )
          test.provide(RemoteContext.inMemory)
        },
      ),
      suite("SwapEither")(
        test("evaluates correctly when it is Left") {
          val remote = Remote.SwapEither(Remote.Either0(Left((Remote("test"), SchemaOrNothing.fromSchema(Schema[Int])))))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[Int, String]]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema.either[Int, String], Right("test")),
              typ == Right("test")
            )

          test.provide(RemoteContext.inMemory)
        },
        test("evaluates correctly when it is Right") {
          val remote = Remote.SwapEither(Remote.Either0(Right((SchemaOrNothing.fromSchema(Schema[Int]), (Remote("test"))))))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[String, Int]]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema.either[String, Int], Left("test")),
              typ == Left("test")
            )

          test.provide(RemoteContext.inMemory)
        }
      ),
    )
}
