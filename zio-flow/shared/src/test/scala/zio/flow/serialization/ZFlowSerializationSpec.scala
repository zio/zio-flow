/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.serialization

import zio.ZIO
import zio.constraintless.TypeList._
import zio.flow.ZFlow
import zio.schema.codec.JsonCodec.{JsonDecoder, JsonEncoder}
import zio.schema.codec.{BinaryCodecs, JsonCodec, ProtobufCodec}
import zio.test._

import java.nio.charset.StandardCharsets

object ZFlowSerializationSpec extends ZIOSpecDefault with Generators {

  private def jsonCodecs: BinaryCodecs[ZFlow[Any, Any, Any] :: End] = {
    import JsonCodec.schemaBasedBinaryCodec
    BinaryCodecs.make[ZFlow[Any, Any, Any] :: End]
  }

  private def protobufCodecs: BinaryCodecs[ZFlow[Any, Any, Any] :: End] = {
    import ProtobufCodec.protobufCodec
    BinaryCodecs.make[ZFlow[Any, Any, Any] :: End]
  }

  override def spec: Spec[TestEnvironment, Any] =
    suite("ZFlow serialization")(
      suite("roundtrip equality")(
        equalityWithCodec("JSON", jsonCodecs),
        equalityWithCodec("Protobuf", protobufCodecs)
      ),
      test("ZFlow schema is serializable") {
        val schema = ZFlow.schema[Any, Any, Any]
        val serialized =
          JsonEncoder.encode(FlowSchemaAst.schema, FlowSchemaAst.fromSchema(schema), JsonCodec.Config.default)
        val deserialized =
          JsonDecoder.decode(FlowSchemaAst.schema, new String(serialized.toArray, StandardCharsets.UTF_8))
        val deserializedSchema = deserialized.map(_.toSchema)
        val refEq              = schema eq deserializedSchema.toOption.get
        assertTrue(refEq)
      }
    )

  private def equalityWithCodec(
    label: String,
    codec: BinaryCodecs[ZFlow[Any, Any, Any] :: End]
  ): Spec[Sized with TestConfig, TestSuccess] =
    suite(label)(
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
      test("Iterate")(roundtripCheck(codec, genZFlowIterate)),
      test("Random")(roundtripCheck(codec, genZFlowRandom)),
      test("RandomUUID")(roundtripCheck(codec, genZFlowRandomUUID))
    )

  private def roundtripCheck(
    codec: BinaryCodecs[ZFlow[Any, Any, Any] :: End],
    gen: Gen[Sized, ZFlow[Any, Any, Any]]
  ): ZIO[Sized with TestConfig, Nothing, TestResult] =
    check(gen) { value =>
      roundtrip(codec, value)
    }

  private def roundtrip(codec: BinaryCodecs[ZFlow[Any, Any, Any] :: End], value: ZFlow[Any, Any, Any]): TestResult = {
    val encoded = codec.encode(value)
    val decoded = codec.decode(encoded)

    // println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    assertTrue(decoded == Right(value))
  }
}
