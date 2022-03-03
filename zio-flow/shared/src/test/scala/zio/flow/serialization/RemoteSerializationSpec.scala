package zio.flow.serialization

import zio.Random
import zio.flow.Remote
import zio.schema.codec.{Codec, JsonCodec, ProtobufCodec}
import zio.test._

object RemoteSerializationSpec extends DefaultRunnableSpec with Generators {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Remote serialization")(
      withCodec(JsonCodec),
      withCodec(ProtobufCodec),
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
      }
    )

  private def roundtrip[A](codec: Codec, value: Remote[Any]): Assert = {
    val encoded = codec.encode(Remote.schemaRemote)(value)
    val decoded = codec.decode(Remote.schemaRemote)(encoded)

    println(s"$value => $decoded")

    assertTrue(decoded == Right(value))
  }

}
