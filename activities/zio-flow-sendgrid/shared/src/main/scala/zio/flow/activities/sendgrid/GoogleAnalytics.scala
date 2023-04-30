package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class GoogleAnalytics(
  enable: Boolean,
  utm_source: String,
  utm_medium: String,
  utm_term: String,
  utm_content: String,
  utm_campaign: String
)

object GoogleAnalytics {
  def derivedSchema                            = DeriveSchema.gen[GoogleAnalytics]
  implicit val schema: Schema[GoogleAnalytics] = derivedSchema

  val (enable, utm_source, utm_medium, utm_term, utm_content, utm_campaign) =
    Remote.makeAccessors[GoogleAnalytics](derivedSchema)
}
