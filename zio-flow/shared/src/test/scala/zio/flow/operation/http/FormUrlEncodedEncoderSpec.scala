package zio.flow.operation.http

import zio.Chunk
import zio.schema.{DeriveSchema, Schema}
import zio.test.{ZIOSpecDefault, assertTrue}

import java.time.Instant
import java.util.UUID

object FormUrlEncodedEncoderSpec extends ZIOSpecDefault {

  case class Test1(name: String, id: UUID, count: Option[Int], timestamp: Instant)
  object Test1 {
    implicit val schema: Schema[Test1] = DeriveSchema.gen
  }

  override def spec =
    suite("FormUrlEncodedEncoder")(
      test("string") {
        val encoder: String => Chunk[Byte] = FormUrlEncodedEncoder.encode(Schema[String])
        val str                            = new String(encoder("hello world").toArray)
        assertTrue(str == "hello+world")
      },
      test("int") {
        val str = new String(FormUrlEncodedEncoder.encode(Schema[Int])(123).toArray)
        assertTrue(str == "123")
      },
      test("record") {
        val record = Test1(
          name = "test name",
          id = UUID.fromString("1632698e-19c6-4718-98cd-839605309252"),
          count = Some(100),
          timestamp = Instant.ofEpochSecond(1664665860L)
        )
        val str = new String(
          FormUrlEncodedEncoder.encode(Schema[Test1])(record).toArray
        )
        assertTrue(
          str == "name=test+name&id=1632698e-19c6-4718-98cd-839605309252&count=100&timestamp=2022-10-01T23%3A11%3A00Z"
        )
      },
      test("record with None field") {
        val record = Test1(
          name = "test name",
          id = UUID.fromString("1632698e-19c6-4718-98cd-839605309252"),
          count = None,
          timestamp = Instant.ofEpochSecond(1664665860L)
        )
        val str = new String(
          FormUrlEncodedEncoder.encode(Schema[Test1])(record).toArray
        )
        assertTrue(
          str == "name=test+name&id=1632698e-19c6-4718-98cd-839605309252&timestamp=2022-10-01T23%3A11%3A00Z"
        )
      }
    )
}
