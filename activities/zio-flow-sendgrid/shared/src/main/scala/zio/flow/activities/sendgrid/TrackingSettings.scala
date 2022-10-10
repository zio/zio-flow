package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class TrackingSettings(
  click_tracking: Option[ClickTracking],
  open_tracking: Option[OpenTracking],
  subscription_tracking: Option[SubscriptionTracking],
  ganalytics: Option[GoogleAnalytics]
)

object TrackingSettings {
  implicit val schema = DeriveSchema.gen[TrackingSettings]

  val (click_tracking, open_tracking, subscription_tracking, ganalytics) = Remote.makeAccessors[TrackingSettings]
}
