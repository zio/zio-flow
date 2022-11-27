package zio.flow.server.flows.model

import zio.flow.FlowId
import zio.flow.runtime.FlowStatus
import zio.schema.{DeriveSchema, Schema}

final case class GetAllResponse(flows: Map[FlowId, FlowStatus])

object GetAllResponse {
  implicit val schema: Schema[GetAllResponse] = DeriveSchema.gen
}
