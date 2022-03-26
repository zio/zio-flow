package zio.flow.serialization

import zio.flow.ZFlow
import zio.{Random, ZIO}
import zio.schema.codec.{Codec, JsonCodec}
import zio.test._

object ZFlowSerializationSpec extends DefaultRunnableSpec with Generators {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("ZFlow serialization")(
      suite("roundtrip equality")(
        equalityWithCodec(JsonCodec)
        // equalityWithCodec(ProtobufCodec) // TODO: fix
      )
    )

  private def equalityWithCodec(
    codec: Codec
  ): Spec[Random with Sized with TestConfig, TestFailure[String], TestSuccess] =
    suite(codec.getClass.getSimpleName)(
      test("Return")(roundtripCheck(codec, genZFlowReturn)),
      test("Now")(roundtripCheck(codec, genZFlowNow)),
      test("WaitTill")(roundtripCheck(codec, genZFlowWaitTill)),
      test("Modify")(roundtripCheck(codec, genZFlowModify)),
      test("Fold")(roundtripCheck(codec, genZFlowFold)),
      test("Apply")(roundtripCheck(codec, genZFlowApply)),
      test("Log")(roundtripCheck(codec, genZFlowLog)),
      test("RunActivity")(roundtripCheck(codec, genZFlowRunActivity)),
      test("Fail")(roundtripCheck(codec, genZFlowFail))
    )

  private def roundtripCheck(
    codec: Codec,
    gen: Gen[Random with Sized, ZFlow[Any, Any, Any]]
  ): ZIO[Random with Sized with TestConfig, Nothing, TestResult] =
    check(gen) { value =>
      roundtrip(codec, value)
    }

  private def roundtrip(codec: Codec, value: ZFlow[Any, Any, Any]): Assert = {
    val encoded = codec.encode(ZFlow.schema[Any, Any, Any])(value)
    val decoded = codec.decode(ZFlow.schema[Any, Any, Any])(encoded)

    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    assertTrue(decoded == Right(value))
  }
}
