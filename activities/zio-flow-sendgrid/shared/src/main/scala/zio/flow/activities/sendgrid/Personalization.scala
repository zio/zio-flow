package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.DeriveSchema

final case class Personalization(
  from: Option[EmailAddress] = None,
  to: List[EmailAddress],
  cc: Option[List[EmailAddress]] = None,
  bcc: Option[List[EmailAddress]] = None,
  subject: Option[String] = None,
  // headers: Map[String, String], // TODO: this should be a dynamic Json object - is DynamicValue applicable here?
  // substitutions: Map[String, String], // TODO: this should be a dynamic Json object - is DynamicValue applicable here?
  // dynamic_template_data: Map[String, String], // TODO: this should be a dynamic Json object - is DynamicValue applicable here?
  // custom_args: Map[String, String], // TODO: this should be a dynamic Json object - is DynamicValue applicable here?
  send_at: Option[Int] = None // TODO: should be instant
)

object Personalization {
  implicit val schema = DeriveSchema.gen[Personalization]

  val (from, to, cc, bcc, subject, send_at) = Remote.makeAccessors[Personalization]
}
