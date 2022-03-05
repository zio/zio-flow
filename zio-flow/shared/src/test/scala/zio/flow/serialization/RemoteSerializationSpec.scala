package zio.flow.serialization

import zio.Random
import zio.flow.Remote
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
        check(genAddNumeric) { variable =>
          roundtrip(codec, variable)
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
