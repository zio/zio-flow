package zio.flow.serialization

import zio.Random
import zio.flow.Remote
import zio.schema.Schema
import zio.schema.codec.{Codec, JsonCodec, ProtobufCodec}
import zio.test._

object RemoteSerializationSpec extends DefaultRunnableSpec with Generators {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Remote serialization")(
      withCodec(JsonCodec)
//      withCodec(ProtobufCodec) // TODO: fix
    )

  private def withCodec(codec: Codec): Spec[Random with Sized with TestConfig, TestFailure[Nothing], TestSuccess] =
    suite(codec.getClass.getSimpleName)(
      test("literal") {
        check(genLiteral) { literal =>
          roundtrip(codec, literal)
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

  private def roundtrip[A](codec: Codec, value: Remote[Any]): Assert = {
    val encoded = codec.encode(Remote.schemaRemoteAny)(value)
    val decoded = codec.decode(Remote.schemaRemoteAny)(encoded)

    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    assertTrue(decoded == Right(value))
  }

}
