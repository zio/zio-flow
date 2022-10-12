package zio.flow

import zio.flow.operation.http.FormUrlEncodedEncoder
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import zio.test.{TestResult, assertTrue}

package object test {
  def assertFormUrlEncoded[A: Schema](value: A, expected: String): TestResult =
    assertTrue(
      new String(FormUrlEncodedEncoder.encode(implicitly[Schema[A]])(value).toArray) == expected
    )

  def assertJsonSerializable[A: Schema](value: A): TestResult =
    assertTrue(
      JsonCodec.decode(implicitly[Schema[A]])(JsonCodec.encode(implicitly[Schema[A]])(value)) == Right(value)
    )
}
