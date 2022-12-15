package zio.flow

import zio.flow.operation.http.FormUrlEncodedEncoder
import zio.schema.Schema
import zio.schema.codec.JsonCodec.{JsonDecoder, JsonEncoder}
import zio.test.{TestResult, assertTrue}

import java.nio.charset.StandardCharsets

package object test {
  def assertFormUrlEncoded[A: Schema](value: A, expected: String): TestResult =
    assertTrue(
      new String(FormUrlEncodedEncoder.encode(implicitly[Schema[A]])(value).toArray) == expected
    )

  def assertJsonSerializable[A: Schema](value: A): TestResult =
    assertTrue(
      JsonDecoder.decode(
        implicitly[Schema[A]],
        new String(JsonEncoder.encode(implicitly[Schema[A]], value).toArray, StandardCharsets.UTF_8)
      ) == Right(value)
    )
}
