package zio.flow.server

import zio.flow.FlowId
import zio.schema.{DeriveSchema, Schema}

final case class ZFlowTriggered(flowId: FlowId)

object ZFlowTriggered {
  implicit def schema: Schema[ZFlowTriggered] = DeriveSchema.gen
}
