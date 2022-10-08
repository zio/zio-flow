package zio.flow.activities.twilio

import zio.Scope
import zio.flow.test._
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault}

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
      )
    )
}
