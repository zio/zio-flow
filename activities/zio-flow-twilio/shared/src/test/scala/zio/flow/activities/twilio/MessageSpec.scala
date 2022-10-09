package zio.flow.activities.twilio

import zio.Chunk
import zio.schema.codec.JsonCodec
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.charset.StandardCharsets

object MessageSpec extends ZIOSpecDefault {
  private val example: String =
    """{
      |  "account_sid": "ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
      |  "api_version": "2010-04-01",
      |  "body": "Hi there",
      |  "date_created": "Thu, 30 Jul 2015 20:12:31 +0000",
      |  "date_sent": "Thu, 30 Jul 2015 20:12:33 +0000",
      |  "date_updated": "Thu, 30 Jul 2015 20:12:33 +0000",
      |  "direction": "outbound-api",
      |  "error_code": null,
      |  "error_message": null,
      |  "from": "+15017122661",
      |  "messaging_service_sid": "MGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
      |  "num_media": "0",
      |  "num_segments": "1",
      |  "price": null,
      |  "price_unit": null,
      |  "sid": "SMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
      |  "status": "sent",
      |  "subresource_uris": {
      |    "media": "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Messages/SMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Media.json"
      |  },
      |  "to": "+15558675310",
      |  "uri": "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Messages/SMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX.json"
      |}""".stripMargin

  override def spec =
    suite("Message")(
      test("can be deserialized from JSON")(
        assertTrue(
          JsonCodec.decode(Message.schema)(Chunk.fromArray(example.getBytes(StandardCharsets.UTF_8))) ==
            Right(
              Message(
                account_sid = AccountSid("ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
                api_version = "2010-04-01",
                body = "Hi there",
                date_created = "Thu, 30 Jul 2015 20:12:31 +0000",
                date_sent = "Thu, 30 Jul 2015 20:12:33 +0000",
                date_updated = "Thu, 30 Jul 2015 20:12:33 +0000",
                direction = "outbound-api",
                error_code = None,
                error_message = None,
                from = PhoneNumber("+15017122661"),
                messaging_service_sid = MessagingServiceSid("MGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
                num_media = "0",
                num_segments = "1",
                price = None,
                price_unit = None,
                sid = MessageSid("SMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
                status = "sent",
//                subresource_uris = Map(
//                  "media" -> "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Messages/SMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Media.json"
//                ),
                to = PhoneNumber("+15558675310"),
                uri =
                  "/2010-04-01/Accounts/ACXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX/Messages/SMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX.json"
              )
            )
        )
      )
    )
}
