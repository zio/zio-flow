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
      test("Transaction")(roundtripCheck(codec, genZFlowTransaction)),
      test("Input")(roundtripCheck(codec, genZFlowInput)),
      test("Ensuring")(roundtripCheck(codec, genZFlowEnsuring)),
      test("Unwrap")(roundtripCheck(codec, genZFlowUnwrap)),
      test("UnwrapRemote")(roundtripCheck(codec, genZFlowUnwrapRemote)),
      test("Fork")(roundtripCheck(codec, genZFlowFork)),
      test("Timeout")(roundtripCheck(codec, genZFlowTimeout)),
      test("Provide")(roundtripCheck(codec, genZFlowProvide)),
      test("Die")(roundtripCheck(codec, genZFlowDie)),
      test("RetryUntil")(roundtripCheck(codec, genZFlowRetryUntil)),
      test("OrTry")(roundtripCheck(codec, genZFlowOrTry)),
      test("Await")(roundtripCheck(codec, genZFlowAwait)),
      test("Interrupt")(roundtripCheck(codec, genZFlowInterrupt)),
      test("NewVar")(roundtripCheck(codec, genZFlowNewVar)),
      test("Fail")(roundtripCheck(codec, genZFlowFail)),
      test("Iterate")(roundtripCheck(codec, genZFlowIterate)),
      test("GetExecutionEnvironment")(roundtripCheck(codec, genZFlowGetExecutionEnvironment))
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
