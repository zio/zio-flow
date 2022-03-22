package zio.flow.serialization

import zio.{Random, ZIO}
import zio.flow.{Remote, RemoteContext, SchemaOrNothing}
import zio.flow._
import zio.flow.serialization.RemoteSerializationSpec.test
import zio.schema.{DeriveSchema, Schema}
import zio.schema.codec.{Codec, JsonCodec, ProtobufCodec}
import zio.test._

import scala.util.{Failure, Success, Try}

object RemoteSerializationSpec extends DefaultRunnableSpec with Generators {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Remote serialization")(
      suite("roundtrip equality")(
        equalityWithCodec(JsonCodec)
//      equalityWithCodec(ProtobufCodec) // TODO: fix
      ),
      suite("roundtrip evaluation")(
        evalWithCodec(JsonCodec)
        //      evalWithCodec(ProtobufCodec) // TODO: fix
      )
    )

  case class TestCaseClass(a: String, b: Int)
  object TestCaseClass {
    val gen: Gen[Random with Sized, TestCaseClass] =
      for {
        a <- Gen.string
        b <- Gen.int
      } yield TestCaseClass(a, b)

    implicit val schema: Schema[TestCaseClass] = DeriveSchema.gen
  }

  private def equalityWithCodec(
    codec: Codec
  ): Spec[Random with Sized with TestConfig, TestFailure[String], TestSuccess] =
    suite(codec.getClass.getSimpleName)(
      test("literal") {
        check(genLiteral) { literal =>
          roundtrip(codec, literal)
        }
      },
      test("literal user type") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote(data)
          roundtripEval(codec, literal).provide(RemoteContext.inMemory)
        }
      },
      test("ignore") {
        roundtrip(codec, Remote.Ignore())
      },
      test("variable")(roundtripCheck(codec, genRemoteVariable)),
      test("add numeric")(roundtripCheck(codec, genAddNumeric)),
      test("div numeric")(roundtripCheck(codec, genDivNumeric)),
      test("mul numeric")(roundtripCheck(codec, genMulNumeric)),
      test("pow numeric")(roundtripCheck(codec, genPowNumeric)),
      test("negation numeric")(roundtripCheck(codec, genNegationNumeric)),
      test("root numeric")(roundtripCheck(codec, genRootNumeric)),
      test("log numeric")(roundtripCheck(codec, genLogNumeric)),
      test("absolute numeric")(roundtripCheck(codec, genAbsoluteNumeric)),
      test("mod numeric")(roundtripCheck(codec, genModNumeric)),
      test("min numeric")(roundtripCheck(codec, genMinNumeric)),
      test("max numeric")(roundtripCheck(codec, genMaxNumeric)),
      test("floor numeric")(roundtripCheck(codec, genFloorNumeric)),
      test("ceil numeric")(roundtripCheck(codec, genCeilNumeric)),
      test("round numeric")(roundtripCheck(codec, genRoundNumeric)),
      test("sin fractional")(roundtripCheck(codec, genSinFractional)),
      test("sin inverse fractional")(roundtripCheck(codec, genSinInverseFractional)),
      test("tan inverse fractional")(roundtripCheck(codec, genTanInverseFractional)),
      test("evaluated remote function")(roundtripCheck(codec, genEvaluatedRemoteFunction)),
      test("remote apply")(roundtripCheck(codec, genRemoteApply)),
      test("either0")(roundtripCheck(codec, genEither0)),
      test("flatMapEither")(roundtripCheck(codec, genFlatMapEither)),
      test("foldEither")(roundtripCheck(codec, genFoldEither)),
      test("swapEither")(roundtripCheck(codec, genSwapEither)),
      test("try")(roundtripCheck(codec, genTry)),
      test("tuple2")(roundtripCheck(codec, genTuple2)),
      test("tuple3")(roundtripCheck(codec, genTuple3)),
      test("tuple4")(roundtripCheck(codec, genTuple4)),
      test("first")(roundtripCheck(codec, genFirst)),
      test("second")(roundtripCheck(codec, genSecond)),
      test("first of tuple3")(roundtripCheck(codec, genFirstOf3)),
      test("second of tuple3")(roundtripCheck(codec, genSecondOf3)),
      test("third of tuple3")(roundtripCheck(codec, genThirdOf3)),
      test("first of tuple4")(roundtripCheck(codec, genFirstOf4)),
      test("second of tuple4")(roundtripCheck(codec, genSecondOf4)),
      test("third of tuple4")(roundtripCheck(codec, genThirdOf4)),
      test("fourth of tuple4")(roundtripCheck(codec, genFourthOf4)),
      test("branch")(roundtripCheck(codec, genBranch)),
      test("length")(roundtripCheck(codec, genLength)),
      test("less than equal")(roundtripCheck(codec, genLessThanEqual)),
      test("equal")(roundtripCheck(codec, genEqual)),
      test("not")(roundtripCheck(codec, genNot)),
      test("and")(roundtripCheck(codec, genAnd)),
      test("fold")(roundtripCheck(codec, genFold)),
      test("cons")(roundtripCheck(codec, genCons)),
      test("uncons")(roundtripCheck(codec, genUnCons)),
      test("instant from long")(roundtripCheck(codec, genInstantFromLong)),
      test("instant from longs")(roundtripCheck(codec, genInstantFromLongs)),
      test("instant from milli")(roundtripCheck(codec, genInstantFromMilli)),
      test("instant from string")(roundtripCheck(codec, genInstantFromString)),
      test("instant to tuple")(roundtripCheck(codec, genInstantToTuple)),
      test("instant plus duration")(roundtripCheck(codec, genInstantPlusDuration)),
      test("instant minus duration")(roundtripCheck(codec, genInstantMinusDuration)),
      test("duration from string")(roundtripCheck(codec, genDurationFromString)),
      test("duration from long")(roundtripCheck(codec, genDurationFromLong)),
      test("duration from longs")(roundtripCheck(codec, genDurationFromLongs)),
      test("duration from big decimal")(roundtripCheck(codec, genDurationFromBigDecimal)),
      test("duration to longs")(roundtripCheck(codec, genDurationToLongs)),
      test("duration to long")(roundtripCheck(codec, genDurationToLong))
    )

  private def evalWithCodec(codec: Codec): Spec[Random with Sized with TestConfig, TestFailure[String], TestSuccess] =
    suite(codec.getClass.getSimpleName)(
      test("literal user type") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote(data)
          roundtripEval(codec, literal).provide(RemoteContext.inMemory)
        }
      },
      test("try") {
        check(Gen.either(Gen.string, Gen.int)) { either =>
          val remote: Remote.Try[Int] = Remote.Try(
            either.fold(
              msg => {
                val throwable: Throwable = new Generators.TestException(msg)
                Left((Remote(throwable), SchemaOrNothing.fromSchema[Int]))
              },
              value => Right(value)
            )
          )

          def compare(a: Try[Int], b: Try[Int]): Boolean =
            (a, b) match {
              case (Success(x), Success(y)) => x == y
              case (Failure(x), Failure(y)) => x.getMessage == y.getMessage
              case _                        => false
            }

          roundtripEval(codec, remote, compare)(schemaTry[Int]).provide(RemoteContext.inMemory)
        }
      },
      test("first") {
        check(TestCaseClass.gen zip Gen.string) { case (a, b) =>
          val first = Remote.First(Remote.Tuple2(Remote(a), Remote(b)))
          roundtripEval(codec, first).provide(RemoteContext.inMemory)
        }
      },
      test("second") {
        check(TestCaseClass.gen zip Gen.string) { case (a, b) =>
          val first = Remote.Second(Remote.Tuple2(Remote(b), Remote(a)))
          roundtripEval(codec, first).provide(RemoteContext.inMemory)
        }
      },
      test("instant truncation") {
        check(Gen.instant zip genSmallChronoUnit) { case (instant, chronoUnit) =>
          val remote = Remote.InstantTruncate(Remote(instant), Remote(chronoUnit))
          roundtripEval(codec, remote).provide(RemoteContext.inMemory)
        }
      },
      test("duration from amount") {
        check(Gen.int zip genSmallChronoUnit) { case (amount, chronoUnit) =>
          val remote = Remote.DurationFromAmount(Remote(amount.toLong), Remote(chronoUnit))
          roundtripEval(codec, remote).provide(RemoteContext.inMemory)
        }
      }
    )

  private def roundtripCheck(
    codec: Codec,
    gen: Gen[Random with Sized, Remote[Any]]
  ): ZIO[Random with Sized with TestConfig, Nothing, TestResult] =
    check(gen) { value =>
      roundtrip(codec, value)
    }

  private def roundtrip(codec: Codec, value: Remote[Any]): Assert = {
    val encoded = codec.encode(Remote.schemaRemoteAny)(value)
    val decoded = codec.decode(Remote.schemaRemoteAny)(encoded)

    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    assertTrue(decoded == Right(value))
  }

  private def roundtripEval[A: Schema](
    codec: Codec,
    value: Remote[A],
    test: (A, A) => Boolean = (a: A, b: A) => a == b
  ): ZIO[RemoteContext, String, Assert] = {
    val encoded = codec.encode(Remote.schemaRemoteAny)(value)
    val decoded = codec.decode(Remote.schemaRemoteAny)(encoded)

//    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    for {
      originalEvaluated <- value.eval[A]
      newRemote         <- ZIO.fromEither(decoded)
      newEvaluated      <- newRemote.asInstanceOf[Remote[A]].eval[A]
    } yield assertTrue(test(originalEvaluated, newEvaluated))
  }

}
