package zio.flow.serialization

import zio.{Random, ZIO}
import zio.flow.{Remote, RemoteContext, SchemaOrNothing}
import zio.flow._
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
