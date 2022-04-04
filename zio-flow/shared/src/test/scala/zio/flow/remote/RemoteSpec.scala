package zio.flow.remote

import zio.flow.serialization.RemoteSerializationSpec.TestCaseClass
import zio.flow.{Remote, RemoteContext, RemoteVariableName, SchemaAndValue}
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
          val remote = Remote.Either0(Left((Remote("test"), Schema[Int])))
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
          val remote = Remote.Either0(Right((Schema[Int], (Remote("test")))))
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
            Remote.Either0(Left((Remote("test"), Schema[Int]))),
            Remote
              .RemoteFunction((a: Remote[Int]) =>
                Remote.Either0(
                  Right((Schema[String], Remote.AddNumeric(a, Remote(1), Numeric.NumericInt)))
                )
              )
              .evaluated,
            Schema[String],
            Schema[Int]
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
            Remote.Either0(Right((Schema[String], Remote(10)))),
            Remote
              .RemoteFunction((a: Remote[Int]) =>
                Remote.Either0(
                  Right((Schema[String], Remote.AddNumeric(a, Remote(1), Numeric.NumericInt)))
                )
              )
              .evaluated,
            Schema[String],
            Schema[Int]
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
        }
      ),
      suite("SwapEither")(
        test("evaluates correctly when it is Left") {
          val remote =
            Remote.SwapEither(Remote.Either0(Left((Remote("test"), Schema[Int]))))
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
          val remote =
            Remote.SwapEither(Remote.Either0(Right((Schema[Int], (Remote("test"))))))
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
      suite("Tuple3")(
        test("evaluates correctly") {
          val remote = Remote.Tuple3(
            Remote("hello"),
            Remote(TestCaseClass("x", 2)),
            Remote(1)
          )
          val expectedValue = ("hello", TestCaseClass("x", 2), 1)
          val expectedDynamicValue =
            SchemaAndValue.fromSchemaAndValue(Schema.tuple3[String, TestCaseClass, Int], expectedValue)
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[(String, TestCaseClass, Int)]
            } yield assertTrue(
              dyn == expectedDynamicValue,
              typ == expectedValue
            )

          test.provide(RemoteContext.inMemory)
        }
      ),
      suite("LessThanEqual")(
        test("evaluates correctly when less") {
          val remote = Remote.LessThanEqual(Remote(5), Remote(10))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Boolean]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema[Boolean], true),
              typ == true
            )

          test.provide(RemoteContext.inMemory)
        },
        test("evaluates correctly when greater") {
          val remote = Remote.LessThanEqual(Remote(50), Remote(10))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Boolean]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema[Boolean], false),
              typ == false
            )

          test.provide(RemoteContext.inMemory)
        }
      ),
      suite("Fold")(
        test("evaluates correctly") {
          val remote = Remote.Fold(
            Remote(List(10, 20, 30)),
            Remote(100),
            Remote
              .RemoteFunction((tuple: Remote[(Int, Int)]) =>
                Remote.AddNumeric(Remote.First(tuple), Remote.Second(tuple), Numeric.NumericInt)
              )
              .evaluated
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Int]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema[Int], 160),
              typ == 160
            )

          test.provide(RemoteContext.inMemory)
        },
        test("evaluates correctly with custom types") {
          val remote = Remote.Fold(
            Remote(List(TestCaseClass("a", 10), TestCaseClass("b", 20), TestCaseClass("c", 30))),
            Remote(TestCaseClass("d", 40)),
            Remote.RemoteFunction((tuple: Remote[(TestCaseClass, TestCaseClass)]) => Remote.Second(tuple)).evaluated
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[TestCaseClass]
            } yield assertTrue(
              dyn == SchemaAndValue.fromSchemaAndValue(Schema[TestCaseClass], TestCaseClass("c", 30)),
              typ == TestCaseClass("c", 30)
            )

          test.provide(RemoteContext.inMemory)
        }
      ),
      suite("ZipOption")(
        test("evaluates correctly with custom types when both are Some") {
          val remote = Remote.ZipOption(
            Remote.Some0(Remote(TestCaseClass("a", 10))),
            Remote.Some0(Remote(20))
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Option[(TestCaseClass, Int)]]
            } yield assertTrue(
              dyn == SchemaAndValue
                .fromSchemaAndValue(Schema[Option[(TestCaseClass, Int)]], Some((TestCaseClass("a", 10), 20))),
              typ == Some((TestCaseClass("a", 10), 20))
            )

          test.provide(RemoteContext.inMemory)
        },
        test("evaluates correctly with custom types when one of them is None") {
          val remote = Remote.ZipOption(
            Remote.Some0(Remote(TestCaseClass("a", 10))),
            Remote[Option[Int]](None)
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Option[(TestCaseClass, Int)]]
            } yield assertTrue(
              dyn == SchemaAndValue
                .fromSchemaAndValue(Schema[Option[(TestCaseClass, Int)]], None),
              typ == None
            )

          test.provide(RemoteContext.inMemory)
        },
        test("evaluates correctly with custom types when both of them are None") {
          val remote = Remote.ZipOption[TestCaseClass, Int](
            Remote[Option[TestCaseClass]](None),
            Remote[Option[Int]](None)
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Option[(TestCaseClass, Int)]]
            } yield assertTrue(
              dyn == SchemaAndValue
                .fromSchemaAndValue(Schema[Option[(TestCaseClass, Int)]], None),
              typ == None
            )

          test.provide(RemoteContext.inMemory)
        }
      )
    )
}
