package zio.flow.activities

import zio.flow._
import zio.flow.operation.http._

package object sendgrid {
  val sendgridApiKey: ConfigKey = ConfigKey("authentication.sendgrid.api_key")

  private lazy val sendgridAuthHeader: Remote[String] =
    rs"Bearer " + Remote.config[String](sendgridApiKey)

  lazy val sendMail: Activity[Mail, Unit] =
    Activity(
      "sendgrid_sendMail",
      "Sends an email over SendGrid's v3 Web API",
      operation = Operation.Http(
        "https://api.sendgrid.com",
        API
          .post("v3" / "mail" / "send")
          .header(Header.string("Authorization"))
          .input[Mail]
      ),
      check = Activity.checkNotSupported,
      compensate = Activity.compensateNotSupported
    ).contramap[Mail] { (mail: Remote[Mail]) =>
      (
        sendgridAuthHeader,
        mail
      )
    }
}
