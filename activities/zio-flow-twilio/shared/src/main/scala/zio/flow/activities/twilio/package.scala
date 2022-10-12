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
          .input[CreateMessage](ContentType.`x-www-form-urlencoded`)
          .output[Message]
      ),
      check = Activity.checkNotSupported,
      compensate = deleteMessageActivity.fromInput
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
          .delete("2010-04-01" / "Accounts" / string / "Messages" / string + ".json")
          .header(Header.string("Authorization"))
      ),
      check = Activity.checkNotSupported, // TODO: implement check
      compensate = Activity.compensateNotSupported
    ).contramap[Message] { (message: Remote[Message]) =>
      (
        Remote.config[String](twilioAccountSid),
        MessageSid.value.get(Message.sid.get(message)),
        twilioAuthHeader
      )
    }
}
