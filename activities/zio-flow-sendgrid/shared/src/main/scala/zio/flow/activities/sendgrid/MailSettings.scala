package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class MailSettings(
  bypass_list_management: Option[Setting] = None,
  bypass_spam_management: Option[Setting] = None,
  bypass_bounce_management: Option[Setting] = None,
  bypass_unsubscribe_management: Option[Setting] = None,
  footer: Option[Footer] = None,
  sandbox_mode: Option[Setting] = None
)

object MailSettings {
  def derivedSchema                         = DeriveSchema.gen[MailSettings]
  implicit val schema: Schema[MailSettings] = derivedSchema

  val (
    bypass_list_management,
    bypass_spam_management,
    bypass_bounce_management,
    bypass_unsubscribe_management,
    footer,
    sandbox_mode
  ) = Remote.makeAccessors[MailSettings](derivedSchema)
}
