package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

// TODO: instead of Option[List[A]]s empty lists as null would be preferable. maybe separate serialization class from the API and convert in activity's contramap
final case class Mail(
  personalizations: List[Personalization],
  from: EmailAddress,
  reply_to: Option[EmailAddress] = None,
  reply_to_list: Option[List[EmailAddress]] = None,
  subject: String,
  content: List[Content],
  attachments: Option[List[Attachment]] = None,
  template_id: Option[String] = None,
  // headers: Map[String, String], // TODO: this should be a dynamic Json object - is DynamicValue applicable here?
  categories: Option[List[CategoryName]] = None,
  custom_args: Option[String] = None,
  send_at: Option[Int] = None, // TODO: should be Instant
  batch_id: Option[BatchId] = None,
  asm: Option[Asm] = None,
  ip_pool_name: Option[String] = None,
  mail_settings: Option[MailSettings] = None,
  tracking_settings: Option[TrackingSettings] = None
)

object Mail {
  implicit val schema = DeriveSchema.gen[Mail]

  val (
    personalizations,
    from,
    reply_to,
    reply_to_list,
    subject,
    content,
    attachments,
    template_id,
    categories,
    custom_args,
    send_at,
    batch_id,
    asm,
    ip_pool_name,
    mail_settings,
    tracking_settings
  ) = Remote.makeAccessors[Mail]
}
