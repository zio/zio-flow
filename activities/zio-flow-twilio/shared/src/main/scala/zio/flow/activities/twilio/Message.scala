package zio.flow.activities.twilio

import zio.flow._
import zio.schema.DeriveSchema

final case class Message(
  body: String,
  num_segments: String,
  direction: String, // TODO: should be Direction serialized into a single string field
  from: PhoneNumber,
  to: PhoneNumber,
  date_updated: String, // TODO: should be Instant and parsed using DateTimeFormatter.RFC_1123_DATE_TIME
  price: Option[String],
  error_message: Option[String],
  uri: String,
  account_sid: AccountSid,
  num_media: String,
  status: String, // TODO: should be Status serialized into a single string field
  messaging_service_sid: MessagingServiceSid,
  sid: MessageSid,
  date_sent: String,    // TODO: should be Instant and parsed using DateTimeFormatter.RFC_1123_DATE_TIME
  date_created: String, // TODO: should be Instant and parsed using DateTimeFormatter.RFC_1123_DATE_TIME
  error_code: Option[Int],
  price_unit: Option[String],
  api_version: String
  // subresource_uris:  Map[String, String] // TODO: this should be a dynamic Json object - is DynamicValue applicable here?
)

object Message {
  implicit val schema = DeriveSchema.gen[Message]

  val (
    body,
    num_segments,
    direction,
    from,
    to,
    date_updated,
    price,
    error_message,
    uri,
    account_sid,
    num_media,
    status,
    messaging_service_sid,
    sid,
    date_sent,
    date_created,
    error_code,
    price_unit,
    api_version
//    subresource_uris
  ) = Remote.makeAccessors[Message]
}
