package zio.flow.server.flows.model

import zio.json.DeriveJsonCodec
import zio.json.ast.Json

final case class SetVariableRequest(value: Json)

object SetVariableRequest {
  implicit val codec: zio.json.JsonCodec[SetVariableRequest] = DeriveJsonCodec.gen[SetVariableRequest]
}
