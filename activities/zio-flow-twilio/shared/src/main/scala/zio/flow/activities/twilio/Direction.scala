package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.DeriveSchema

sealed trait Direction
object Direction {
  case object inbound          extends Direction
  case object `outbound-api`   extends Direction
  case object `outbound-call`  extends Direction
  case object `outbound-reply` extends Direction

  implicit val schema = DeriveSchema.gen[Direction]

  object Accessors {
    implicit val (inbound, outboundApi, outboundCall, outboundReply) = Remote.makeAccessors[Direction]
  }
}
