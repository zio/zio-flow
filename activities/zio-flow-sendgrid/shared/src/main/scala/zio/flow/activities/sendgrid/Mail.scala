package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class Mail private[sendgrid] (
  personalizations: List[Personalization],
  from: EmailAddress,
  reply_to: Option[EmailAddress],
  reply_to_list: List[EmailAddress],
  subject: String,
  content: List[Content],
  attachments: List[Attachment],
  template_id: Option[String],
  //headers: Map[String, String], // TODO: this should be a dynamic Json object - is DynamicValue applicable here?
  categories: List[CategoryName],
  custom_args: Option[String],
  send_at: Option[Int], // TODO: should be Instant
  batch_id: Option[BatchId],
  asm: Option[Asm],
  ip_pool_name: Option[String],
  mail_settings: Option[MailSettings],
  tracking_settings: Option[TrackingSettings]
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
