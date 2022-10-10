package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class SubscriptionTracking(
  enable: Boolean,
  text: Option[String],
  html: Option[String],
  substitution_tag: Option[String]
)

object SubscriptionTracking {
  implicit val schema = DeriveSchema.gen[SubscriptionTracking]

  val (enable, text, html, substitution_tag) = Remote.makeAccessors[SubscriptionTracking]
}
