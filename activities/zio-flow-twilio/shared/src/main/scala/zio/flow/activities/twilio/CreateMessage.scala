package zio.flow.activities.twilio

import zio.schema._
import zio.flow._

final case class CreateMessage(
  To: PhoneNumber,
  Body: String,
  From: Option[PhoneNumber] = None,
  MessagingServiceSid: Option[MessagingServiceSid] = None,
  MediaUrl: Option[MediaUrl] = None,
  MaxPrice: Option[BigDecimal] = None,
  ProvideFeedback: Option[Boolean] = None,
  Attempt: Option[Int] = None,
  ValidityPeriod: Option[Seconds] = None,
  SmartEncoded: Option[Boolean] = None,
  SendAt: Option[Instant] = None,
  SendAsMms: Option[Boolean] = None,
  StatusCallback: Option[CallbackUrl] = None,
  ApplicationSid: Option[ApplicationSid] = None,
  ContentRetention: Option[Retention] = None,
  AddressRetention: Option[Retention] = None,
  ScheduleType: Option[MessageScheduleType] = None
) {
  assert(From.isDefined || MessagingServiceSid.isDefined)
}
// TODO: contentRetention, addressRetention, scheduleType

object CreateMessage {
  def derivedSchema                          = DeriveSchema.gen[CreateMessage]
  implicit val schema: Schema[CreateMessage] = derivedSchema

  val (
    to,
    body,
    from,
    messagingServiceSid,
    mediaUrl,
    maxPrice,
    provideFeedback,
    attempt,
    validityPeriod,
    smartEncoded,
    sendAt,
    sendAsMms,
    statusCallback,
    applicationSid,
    contentRetention,
    addressRetention,
    scheduleType
  ) =
    Remote.makeAccessors[CreateMessage](derivedSchema)
}
