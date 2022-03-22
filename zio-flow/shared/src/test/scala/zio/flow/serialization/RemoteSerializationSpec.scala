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
      test("variable") {
        check(genRemoteVariable) { variable =>
          roundtrip(codec, variable)
        }
      },
      test("add numeric") {
        check(genAddNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("div numeric") {
        check(genDivNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("mul numeric") {
        check(genMulNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("pow numeric") {
        check(genPowNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("negation numeric") {
        check(genNegationNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("root numeric") {
        check(genRootNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("log numeric") {
        check(genLogNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("absolute numeric") {
        check(genAbsoluteNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("mod numeric") {
        check(genModNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("min numeric") {
        check(genMinNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("max numeric") {
        check(genMaxNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("floor numeric") {
        check(genFloorNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("ceil numeric") {
        check(genCeilNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("round numeric") {
        check(genRoundNumeric) { num =>
          roundtrip(codec, num)
        }
      },
      test("sin fractional") {
        check(genSinFractional) { frac =>
          roundtrip(codec, frac)
        }
      },
      test("sin inverse fractional") {
        check(genSinInverseFractional) { frac =>
          roundtrip(codec, frac)
        }
      },
      test("tan inverse fractional") {
        check(genTanInverseFractional) { frac =>
          roundtrip(codec, frac)
        }
      },
      test("evaluated remote function") {
        check(genEvaluatedRemoteFunction) { f =>
          roundtrip(codec, f)
        }
      },
      test("remote apply") {
        check(genRemoteApply) { remoteApply =>
          roundtrip(codec, remoteApply)
        }
      },
      test("either0") {
        check(genEither0) { either0 =>
          roundtrip(codec, either0)
        }
      },
      test("flatMapEither") {
        check(genFlatMapEither) { flatMapEither =>
          roundtrip(codec, flatMapEither)
        }
      },
      test("foldEither") {
        check(genFoldEither) { foldEither =>
          roundtrip(codec, foldEither)
        }
      },
      test("swapEither") {
        check(genSwapEither) { swapEither =>
          roundtrip(codec, swapEither)
        }
      },
      test("try") {
        check(genTry) { t =>
          roundtrip(codec, t)
        }
      },
      test("tuple2") {
        check(genTuple2) { t =>
          roundtrip(codec, t)
        }
      },
      test("tuple3") {
        check(genTuple3) { t =>
          roundtrip(codec, t)
        }
      },
      test("tuple4") {
        check(genTuple4) { t =>
          roundtrip(codec, t)
        }
      },
      test("first") {
        check(genFirst) { first =>
          roundtrip(codec, first)
        }
      },
      test("second") {
        check(genSecond) { second =>
          roundtrip(codec, second)
        }
      },
      test("first of tuple3") {
        check(genFirstOf3) { first =>
          roundtrip(codec, first)
        }
      },
      test("second of tuple3") {
        check(genSecondOf3) { second =>
          roundtrip(codec, second)
        }
      },
      test("third of tuple3") {
        check(genThirdOf3) { third =>
          roundtrip(codec, third)
        }
      },
      test("first of tuple4") {
        check(genFirstOf4) { first =>
          roundtrip(codec, first)
        }
      },
      test("second of tuple4") {
        check(genSecondOf4) { second =>
          roundtrip(codec, second)
        }
      },
      test("third of tuple4") {
        check(genThirdOf4) { third =>
          roundtrip(codec, third)
        }
      },
      test("fourth of tuple4") {
        check(genFourthOf4) { fourth =>
          roundtrip(codec, fourth)
        }
      },
      test("branch") {
        check(genBranch) { fourth =>
          roundtrip(codec, fourth)
        }
      },
      test("length") {
        check(genLength) { length =>
          roundtrip(codec, length)
        }
      },
      test("less than equal") {
        check(genLessThanEqual) { lte =>
          roundtrip(codec, lte)
        }
      },
      test("equal") {
        check(genEqual) { eq =>
          roundtrip(codec, eq)
        }
      },
      test("not") {
        check(genNot) { not =>
          roundtrip(codec, not)
        }
      },
      test("and") {
        check(genAnd) { and =>
          roundtrip(codec, and)
        }
      },
      test("fold") {
        check(genFold) { fold =>
          roundtrip(codec, fold)
        }
      },
      test("cons") {
        check(genCons) { cons =>
          roundtrip(codec, cons)
        }
      },
      test("uncons") {
        check(genUnCons) { uncons =>
          roundtrip(codec, uncons)
        }
      },
      test("instant from long") {
        check(genInstantFromLong) { i =>
          roundtrip(codec, i)
        }
      },
      test("instant from longs") {
        check(genInstantFromLongs) { i =>
          roundtrip(codec, i)
        }
      },
      test("instant from milli") {
        check(genInstantFromMilli) { i =>
          roundtrip(codec, i)
        }
      },
      test("instant from string") {
        check(genInstantFromString) { i =>
          roundtrip(codec, i)
        }
      },
      test("instant to tuple") {
        check(genInstantToTuple) { i =>
          roundtrip(codec, i)
        }
      },
      test("instant plus duration") {
        check(genInstantPlusDuration) { i =>
          roundtrip(codec, i)
        }
      },
      test("instant minus duration") {
        check(genInstantMinusDuration) { i =>
          roundtrip(codec, i)
        }
      },
      test("duration from string") {
        check(genDurationFromString) { d =>
          roundtrip(codec, d)
        }
      },
      test("duration from long") {
        check(genDurationFromLong) { d =>
          roundtrip(codec, d)
        }
      },
      test("duration from longs") {
        check(genDurationFromLongs) { d =>
          roundtrip(codec, d)
        }
      },
      test("duration from big decimal") {
        check(genDurationFromBigDecimal) { d =>
          roundtrip(codec, d)
        }
      }
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

  private def roundtrip[A](codec: Codec, value: Remote[Any]): Assert = {
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
