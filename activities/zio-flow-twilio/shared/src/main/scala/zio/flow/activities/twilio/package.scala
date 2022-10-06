package zio.flow.activities

import zio.flow._
import zio.flow.operation.http._

package object twilio {
  val twilioAccountSid: ConfigKey = ConfigKey("authentication.twilio.account_sid")
  val twilioAuthToken: ConfigKey  = ConfigKey("authentication.twilio.auth_token")

  private lazy val twilioAuthHeader: Remote[String] =
    rs"Basic " + rs"${Remote.config[String](twilioAccountSid)}:${Remote.config[String](twilioAuthToken)}".toBase64

  lazy val sendSMS: Activity[CreateMessage, Message] =
    Activity(
      "twilio_sendSMS",
      "Sends an SMS using a Twilio account",
      operation = Operation.Http(
        host = "https://api.twilio.com",
        API
          .post("2010-04-01" / "Accounts" / string / "Messages.json")
          .header(Header.string("Authorization"))
          .input[CreateMessage]
          .output[Message]
      ),
      check = ZFlow.fail(ActivityError("Check not supported", None)),
      compensate = ZFlow.input[Message].flatMap(deleteMessageActivity(_))
    ).contramap[CreateMessage] { (createMessage: Remote[CreateMessage]) =>
      (
        Remote.config[String](twilioAccountSid),
        twilioAuthHeader,
        createMessage
      )
    }

  lazy val deleteMessageActivity: Activity[Message, Unit] =
    Activity(
      "twilio_deleteSMS",
      "Deletes a scheduled SMS using a Twilio account",
      operation = Operation.Http(
        host = "https://api.twilio.com",
        API
          .delete("2010-04-01" / "Accounts" / string / "Messages" / string) // TODO: .json postfix
          .header(Header.string("Authorization"))
      ),
      check =
        ZFlow.fail(ActivityError("Check not supported", None)), // TODO: provide check that tries getting the message
      compensate = ZFlow.fail(ActivityError("Compensate not supported", None))
    ).contramap[Message] { (_: Remote[Message]) =>
      (
        Remote.config[String](twilioAccountSid),
        rs"message.sid", // TODO: this requires optics support
        twilioAuthHeader
      )
    }

  // TODO: host should be configurable

  // TODO: basic auth helper in the http api?
  // TODO: support application/x-www-form-urlencoded

  // TODO: predefined flows for check/compensate not supported
  // TODO: test json format matches spec

}
