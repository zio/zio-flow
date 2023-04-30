package zio.flow.activities.twilio

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

sealed trait Direction
object Direction {
  case object inbound          extends Direction
  case object `outbound-api`   extends Direction
  case object `outbound-call`  extends Direction
  case object `outbound-reply` extends Direction

  implicit val schema: Schema[Direction] = Accessors.derivedSchema

  object Accessors {
    def derivedSchema                                       = DeriveSchema.gen[Direction]
    val (inbound, outboundApi, outboundCall, outboundReply) = Remote.makeAccessors[Direction](derivedSchema)
  }
}
