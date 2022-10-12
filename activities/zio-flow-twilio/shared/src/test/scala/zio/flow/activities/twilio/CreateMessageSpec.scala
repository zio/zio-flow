package zio.flow.activities.twilio

import zio.Scope
import zio.flow.serialization.FlowSchemaAst
import zio.flow.test._
import zio.schema.{DynamicValue, Schema}
import zio.schema.ast.SchemaAst
import zio.schema.codec.JsonCodec
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}

import java.time.Instant

object CreateMessageSpec extends ZIOSpecDefault {

  private val example1 = CreateMessage(
    From = Some(PhoneNumber("+15017122661")),
    To = PhoneNumber("+15558675310"),
    Body = "Hi there"
  )

  private val example2 = CreateMessage(
    To = PhoneNumber("+15558675310"),
    MessagingServiceSid = Some(MessagingServiceSid("MGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")),
    Body = "This is a scheduled message",
    SendAt = Some(Instant.parse("2021-11-30T20:36:27Z")),
    StatusCallback = Some(CallbackUrl("https://webhook.site/xxxxx")),
    ScheduleType = Some(MessageScheduleType.fixed)
  )

  private val example3 = CreateMessage(
    MessagingServiceSid = Some(MessagingServiceSid("MGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")),
    Body = "This is a scheduled message with an image url",
    MediaUrl = Some(MediaUrl("https://c1.staticflickr.com/3/2899/14341091933_1e92e62d12_b.jpg")),
    SendAt = Some(Instant.parse("2021-11-30T20:36:27Z")),
    To = PhoneNumber("+15558675310"),
    StatusCallback = Some(CallbackUrl("https://webhook.site/xxxxx")),
    ScheduleType = Some(MessageScheduleType.fixed)
  )

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("CreateMessage")(
      suite("is serialized to the expected x-www-form-urlencoded body")(
        test("example1") {
          assertFormUrlEncoded(example1, "To=%2B15558675310&Body=Hi+there&From=%2B15017122661")
        },
        test("example2") {
          assertFormUrlEncoded(
            example2,
            "To=%2B15558675310&Body=This+is+a+scheduled+message&MessagingServiceSid=MGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX&SendAt=2021-11-30T20%3A36%3A27Z&StatusCallback=https%3A%2F%2Fwebhook.site%2Fxxxxx&ScheduleType=fixed"
          )
        },
        test("example3") {
          assertFormUrlEncoded(
            example3,
            "To=%2B15558675310&Body=This+is+a+scheduled+message+with+an+image+url&MessagingServiceSid=MGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX&MediaUrl=https%3A%2F%2Fc1.staticflickr.com%2F3%2F2899%2F14341091933_1e92e62d12_b.jpg&SendAt=2021-11-30T20%3A36%3A27Z&StatusCallback=https%3A%2F%2Fwebhook.site%2Fxxxxx&ScheduleType=fixed"
          )
        }
      ),
      suite("can be converted to/from DynamicValue")(
        test("example1") {
          assertTrue(
            DynamicValue.fromSchemaAndValue(CreateMessage.schema, example1).toTypedValue[CreateMessage] == Right(
              example1
            )
          )
        },
        test("example2") {
          assertTrue(
            DynamicValue.fromSchemaAndValue(CreateMessage.schema, example2).toTypedValue[CreateMessage] == Right(
              example2
            )
          )
        },
        test("example3") {
          assertTrue(
            DynamicValue.fromSchemaAndValue(CreateMessage.schema, example3).toTypedValue[CreateMessage] == Right(
              example3
            )
          )
        }
      ),
      suite("can be converted to/from JSON")(
        test("example1") {
          assertJsonSerializable(example1)
        },
        test("example2") {
          assertJsonSerializable(example2)
        },
        test("example3") {
          assertJsonSerializable(example3)
        }
      ),
      test("schema is serializable") {
        val ast          = CreateMessage.schema.ast
        val roundtripAst = JsonCodec.decode(SchemaAst.schema)(JsonCodec.encode(SchemaAst.schema)(ast))
        val roundtripAst2 =
          JsonCodec.decode(SchemaAst.schema)(JsonCodec.encode(SchemaAst.schema)(roundtripAst.toOption.get.toSchema.ast))
        assertTrue(
          roundtripAst == Right(ast),
          roundtripAst2 == Right(ast),
          Schema.structureEquality.equal(roundtripAst.toOption.get.toSchema, CreateMessage.schema),
          Schema.structureEquality.equal(roundtripAst2.toOption.get.toSchema, CreateMessage.schema)
        )
      },
      test("schema is serializable") {
        val ast          = FlowSchemaAst.fromSchema(CreateMessage.schema)
        val roundtripAst = JsonCodec.decode(FlowSchemaAst.schema)(JsonCodec.encode(FlowSchemaAst.schema)(ast))
        val roundtripAst2 = JsonCodec.decode(FlowSchemaAst.schema)(
          JsonCodec.encode(FlowSchemaAst.schema)(FlowSchemaAst.fromSchema(roundtripAst.toOption.get.toSchema))
        )
        assertTrue(
          roundtripAst == Right(ast),
          roundtripAst2 == Right(ast),
          Schema.structureEquality.equal(roundtripAst.toOption.get.toSchema, CreateMessage.schema),
          Schema.structureEquality.equal(roundtripAst2.toOption.get.toSchema, CreateMessage.schema)
        )
      }
    )
}
