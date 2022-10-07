package zio.flow.activities.twilio

import zio.schema._
import zio.flow._

final case class CreateMessage(
  to: PhoneNumber,
  from: Either[PhoneNumber, MessagingServiceSid],
  body: Either[MediaUrl, String],
  maxPrice: Option[BigDecimal] = None,
  provideFeedback: Option[Boolean] = None,
  attempt: Option[Int] = None,
  validityPeriod: Option[Seconds] = None,
  smartEncoded: Option[Boolean] = None,
  sendAt: Option[Instant] = None,
  sendAsMms: Option[Boolean] = None
)
// TODO: contentRetention, addressRetention, scheduleType
// TODO: get rid of eithers

object CreateMessage {
  implicit val schema = DeriveSchema.gen[CreateMessage]

  val (to, from, body, maxPrice, provideFeedback, attempt, validityPeriod, smartEncoded, sendAt, sendAsMms) =
    Remote.makeAccessors[CreateMessage]
}
