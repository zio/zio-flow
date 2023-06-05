package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

sealed trait Status
object Status {
  case object accepted    extends Status
  case object scheduled   extends Status
  case object canceled    extends Status
  case object queued      extends Status
  case object sending     extends Status
  case object sent        extends Status
  case object failed      extends Status
  case object delivered   extends Status
  case object undelivered extends Status
  case object receiving   extends Status
  case object received    extends Status

  def derivedSchema                   = DeriveSchema.gen[Status]
  implicit val schema: Schema[Status] = derivedSchema

  val (
    _accepted,
    _scheduled,
    _canceled,
    _queued,
    _sending,
    _sent,
    _failed,
    _delivered,
    _undelivered,
    _receiving,
    _received
  ) =
    Remote.makeAccessors[Status](derivedSchema)
}
