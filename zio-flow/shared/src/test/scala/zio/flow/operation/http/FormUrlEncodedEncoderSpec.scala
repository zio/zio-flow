package zio.flow.operation.http

import zio.flow._
import zio.schema.{DeriveSchema, Schema}
import zio.test.{ZIOSpecDefault, assertTrue}

import java.time.Instant
import java.util.UUID

object FormUrlEncodedEncoderSpec extends ZIOSpecDefault {

  case class Test1(name: String, id: UUID, count: Option[Int], timestamp: Instant)
  object Test1 {
    implicit val schema = DeriveSchema.gen[Test1]
  }

  override def spec =
    suite("FormUrlEncodedEncoder")(
      test("string")(
        assertTrue(new String(FormUrlEncodedEncoder.encode(Schema[String])("hello world").toArray) == "hello+world")
      ),
      test("int")(
        assertTrue(new String(FormUrlEncodedEncoder.encode(Schema[Int])(123).toArray) == "123")
      ),
      test("record") {
        val record = Test1(
          name = "test name",
          id = UUID.fromString("1632698e-19c6-4718-98cd-839605309252"),
          count = Some(100),
          timestamp = Instant.ofEpochSecond(1664665860L)
        )
        assertTrue(
          new String(
            FormUrlEncodedEncoder.encode(Schema[Test1])(record).toArray
          ) == "name=test+name&id=1632698e-19c6-4718-98cd-839605309252&count=100&timestamp=2022-10-01T23%3A11%3A00Z"
        )
      },
      test("record with None field") {
        val record = Test1(
          name = "test name",
          id = UUID.fromString("1632698e-19c6-4718-98cd-839605309252"),
          count = None,
          timestamp = Instant.ofEpochSecond(1664665860L)
        )
        assertTrue(
          new String(
            FormUrlEncodedEncoder.encode(Schema[Test1])(record).toArray
          ) == "name=test+name&id=1632698e-19c6-4718-98cd-839605309252&timestamp=2022-10-01T23%3A11%3A00Z"
        )
      }
    )
}
