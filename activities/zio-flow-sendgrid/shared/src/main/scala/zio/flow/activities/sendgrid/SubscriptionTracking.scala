package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class SubscriptionTracking(
  enable: Boolean,
  text: Option[String] = None,
  html: Option[String] = None,
  substitution_tag: Option[String] = None
)

object SubscriptionTracking {
  implicit val schema = DeriveSchema.gen[SubscriptionTracking]

  val (enable, text, html, substitution_tag) = Remote.makeAccessors[SubscriptionTracking]
}
