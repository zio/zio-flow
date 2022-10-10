package zio.flow.activities.sendgrid

import zio.Chunk
import zio.schema.codec.JsonCodec
import zio.test.{ZIOSpecDefault, assertTrue}

import java.nio.charset.StandardCharsets

object MailSpec extends ZIOSpecDefault {

  private val exampleRaw: String =
    """{
      |  "personalizations": [
      |    {
      |      "to": [
      |        {
      |          "email": "john_doe@example.com",
      |          "name": "John Doe"
      |        },
      |        {
      |          "email": "julia_doe@example.com",
      |          "name": "Julia Doe"
      |        }
      |      ],
      |      "cc": [
      |        {
      |          "email": "jane_doe@example.com",
      |          "name": "Jane Doe"
      |        }
      |      ],
      |      "bcc": [
      |        {
      |          "email": "james_doe@example.com",
      |          "name": "Jim Doe"
      |        }
      |      ]
      |    },
      |    {
      |      "from": {
      |        "email": "sales@example.com",
      |        "name": "Example Sales Team"
      |      },
      |      "to": [
      |        {
      |          "email": "janice_doe@example.com",
      |          "name": "Janice Doe"
      |        }
      |      ],
      |      "bcc": [
      |        {
      |          "email": "jordan_doe@example.com",
      |          "name": "Jordan Doe"
      |        }
      |      ]
      |    }
      |  ],
      |  "from": {
      |    "email": "orders@example.com",
      |    "name": "Example Order Confirmation"
      |  },
      |  "reply_to": {
      |    "email": "customer_service@example.com",
      |    "name": "Example Customer Service Team"
      |  },
      |  "subject": "Your Example Order Confirmation",
      |  "content": [
      |    {
      |      "type": "text/html",
      |      "value": "<p>Hello from Twilio SendGrid!</p><p>Sending with the email service trusted by developers and marketers for <strong>time-savings</strong>, <strong>scalability</strong>, and <strong>delivery expertise</strong>.</p><p>%open-track%</p>"
      |    }
      |  ],
      |  "attachments": [
      |    {
      |      "content": "PCFET0NUWVBFIGh0bWw+CjxodG1sIGxhbmc9ImVuIj4KCiAgICA8aGVhZD4KICAgICAgICA8bWV0YSBjaGFyc2V0PSJVVEYtOCI+CiAgICAgICAgPG1ldGEgaHR0cC1lcXVpdj0iWC1VQS1Db21wYXRpYmxlIiBjb250ZW50PSJJRT1lZGdlIj4KICAgICAgICA8bWV0YSBuYW1lPSJ2aWV3cG9ydCIgY29udGVudD0id2lkdGg9ZGV2aWNlLXdpZHRoLCBpbml0aWFsLXNjYWxlPTEuMCI+CiAgICAgICAgPHRpdGxlPkRvY3VtZW50PC90aXRsZT4KICAgIDwvaGVhZD4KCiAgICA8Ym9keT4KCiAgICA8L2JvZHk+Cgo8L2h0bWw+Cg==",
      |      "filename": "index.html",
      |      "type": "text/html",
      |      "disposition": "attachment"
      |    }
      |  ],
      |  "categories": [
      |    "cake",
      |    "pie",
      |    "baking"
      |  ],
      |  "send_at": 1617260400,
      |  "batch_id": "AsdFgHjklQweRTYuIopzXcVBNm0aSDfGHjklmZcVbNMqWert1znmOP2asDFjkl",
      |  "asm": {
      |    "group_id": 12345,
      |    "groups_to_display": [
      |      12345
      |    ]
      |  },
      |  "ip_pool_name": "transactional email",
      |  "mail_settings": {
      |    "bypass_list_management": {
      |      "enable": false
      |    },
      |    "footer": {
      |      "enable": false
      |    },
      |    "sandbox_mode": {
      |      "enable": false
      |    }
      |  },
      |  "tracking_settings": {
      |    "click_tracking": {
      |      "enable": true,
      |      "enable_text": false
      |    },
      |    "open_tracking": {
      |      "enable": true,
      |      "substitution_tag": "%open-track%"
      |    },
      |    "subscription_tracking": {
      |      "enable": false
      |    }
      |  }
      |}""".stripMargin

  private val exampleMail: Mail =
    Mail(
      personalizations = List(
        Personalization(
          to = List(
            EmailAddress(email = "john_doe@example.com", name = "John Doe"),
            EmailAddress(email = "julia_doe@example.com", name = "Julia Doe")
          ),
          cc = Some(
            List(
              EmailAddress(email = "jane_doe@example.com", name = "Jane Doe")
            )
          ),
          bcc = Some(
            List(
              EmailAddress(email = "james_doe@example.com", name = "Jim Doe")
            )
          )
        ),
        Personalization(
          from = Some(
            EmailAddress(
              email = "sales@example.com",
              name = "Example Sales Team"
            )
          ),
          to = List(
            EmailAddress(email = "janice_doe@example.com", name = "Janice Doe")
          ),
          bcc = Some(
            List(
              EmailAddress(email = "jordan_doe@example.com", name = "Jordan Doe")
            )
          )
        )
      ),
      from = EmailAddress(email = "orders@example.com", name = "Example Order Confirmation"),
      reply_to = Some(EmailAddress(email = "customer_service@example.com", name = "Example Customer Service Team")),
      subject = "Your Example Order Confirmation",
      content = List(
        Content(
          `type` = "text/html",
          value =
            "<p>Hello from Twilio SendGrid!</p><p>Sending with the email service trusted by developers and marketers for <strong>time-savings</strong>, <strong>scalability</strong>, and <strong>delivery expertise</strong>.</p><p>%open-track%</p>"
        )
      ),
      attachments = Some(
        List(
          Attachment(
            content =
              "PCFET0NUWVBFIGh0bWw+CjxodG1sIGxhbmc9ImVuIj4KCiAgICA8aGVhZD4KICAgICAgICA8bWV0YSBjaGFyc2V0PSJVVEYtOCI+CiAgICAgICAgPG1ldGEgaHR0cC1lcXVpdj0iWC1VQS1Db21wYXRpYmxlIiBjb250ZW50PSJJRT1lZGdlIj4KICAgICAgICA8bWV0YSBuYW1lPSJ2aWV3cG9ydCIgY29udGVudD0id2lkdGg9ZGV2aWNlLXdpZHRoLCBpbml0aWFsLXNjYWxlPTEuMCI+CiAgICAgICAgPHRpdGxlPkRvY3VtZW50PC90aXRsZT4KICAgIDwvaGVhZD4KCiAgICA8Ym9keT4KCiAgICA8L2JvZHk+Cgo8L2h0bWw+Cg==",
            filename = "index.html",
            `type` = "text/html",
            disposition = "attachment"
          )
        )
      ),
      categories = Some(List(CategoryName("cake"), CategoryName("pie"), CategoryName("baking"))),
      send_at = Some(1617260400),
      batch_id = Some(BatchId("AsdFgHjklQweRTYuIopzXcVBNm0aSDfGHjklmZcVbNMqWert1znmOP2asDFjkl")),
      asm = Some(
        Asm(
          group_id = 12345,
          groups_to_display = List(12345)
        )
      ),
      ip_pool_name = Some("transactional email"),
      mail_settings = Some(
        MailSettings(
          bypass_list_management = Some(Setting(enable = false)),
          footer = Some(Footer(enable = false)),
          sandbox_mode = Some(Setting(enable = false))
        )
      ),
      tracking_settings = Some(
        TrackingSettings(
          click_tracking = Some(
            ClickTracking(
              enable = true,
              enable_text = false
            )
          ),
          open_tracking = Some(
            OpenTracking(
              enable = true,
              substitution_tag = Some("%open-track%")
            )
          ),
          subscription_tracking = Some(SubscriptionTracking(enable = false))
        )
      )
    )

  override def spec =
    suite("Mail")(
      test("can be deserialized from JSON")(
        assertTrue(
          JsonCodec.decode(Mail.schema)(Chunk.fromArray(exampleRaw.getBytes(StandardCharsets.UTF_8))) ==
            Right(exampleMail)
        )
      )
    )
}
