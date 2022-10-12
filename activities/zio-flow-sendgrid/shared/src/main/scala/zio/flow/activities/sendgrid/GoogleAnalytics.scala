package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class GoogleAnalytics(
  enable: Boolean,
  utm_source: String,
  utm_medium: String,
  utm_term: String,
  utm_content: String,
  utm_campaign: String
)

object GoogleAnalytics {
  implicit val schema = DeriveSchema.gen[GoogleAnalytics]

  val (enable, utm_source, utm_medium, utm_term, utm_content, utm_campaign) = Remote.makeAccessors[GoogleAnalytics]
}
