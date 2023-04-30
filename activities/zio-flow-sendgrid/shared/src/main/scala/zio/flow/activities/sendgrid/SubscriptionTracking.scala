package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class SubscriptionTracking(
  enable: Boolean,
  text: Option[String] = None,
  html: Option[String] = None,
  substitution_tag: Option[String] = None
)

object SubscriptionTracking {
  def derivedSchema                                 = DeriveSchema.gen[SubscriptionTracking]
  implicit val schema: Schema[SubscriptionTracking] = derivedSchema

  val (enable, text, html, substitution_tag) = Remote.makeAccessors[SubscriptionTracking](derivedSchema)
}
