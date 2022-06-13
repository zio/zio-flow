package zio.flow.serialization

import zio.ZIO
import zio.flow.ZFlow
import zio.schema.Schema
import zio.schema.ast.SchemaAst
import zio.schema.codec.{Codec, JsonCodec, ProtobufCodec}
import zio.test._

object ZFlowSerializationSpec extends ZIOSpecDefault with Generators {
  override def spec: Spec[TestEnvironment, Any] =
    suite("ZFlow serialization")(
      suite("roundtrip equality")(
        equalityWithCodec(JsonCodec),
        equalityWithCodec(ProtobufCodec)
      ),
      test("ZFlow schema is serializable") {
        val schema             = ZFlow.schema[Any, Any, Any]
        val serialized         = JsonCodec.encode(SchemaAst.schema)(schema.ast)
        val deserialized       = JsonCodec.decode(SchemaAst.schema)(serialized)
        val deserializedSchema = deserialized.map(_.toSchema)
        assertTrue(
          Schema.structureEquality.equal(schema, deserializedSchema.toOption.get)
        )
      } @@ TestAspect.ignore // TODO: fix recursion
    )

  private def equalityWithCodec(
    codec: Codec
  ): Spec[Sized with TestConfig, TestSuccess] =
    suite(codec.getClass.getSimpleName)(
      test("Return")(roundtripCheck(codec, genZFlowReturn)),
      test("Now")(roundtripCheck(codec, genZFlowNow)),
      test("WaitTill")(roundtripCheck(codec, genZFlowWaitTill)),
      test("Read")(roundtripCheck(codec, genZFlowRead)),
      test("Modify")(roundtripCheck(codec, genZFlowModify)),
      test("Fold")(roundtripCheck(codec, genZFlowFold)),
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
      test("Iterate")(roundtripCheck(codec, genZFlowIterate))
    )

  private def roundtripCheck(
    codec: Codec,
    gen: Gen[Sized, ZFlow[Any, Any, Any]]
  ): ZIO[Sized with TestConfig, Nothing, TestResult] =
    check(gen) { value =>
      roundtrip(codec, value)
    }

  private def roundtrip(codec: Codec, value: ZFlow[Any, Any, Any]): TestResult = {
    val encoded = codec.encode(ZFlow.schema[Any, Any, Any])(value)
    val decoded = codec.decode(ZFlow.schema[Any, Any, Any])(encoded)

    //    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    assertTrue(decoded == Right(value))
  }
}
