package zio.flow.server.flows.model

import zio.flow.RemoteVariableName
import zio.json.ast.Json
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder}

import scala.annotation.nowarn

final case class GetVariableResponse(name: RemoteVariableName, value: Json)

object GetVariableResponse {
  @nowarn private implicit val templateIdEncoder: JsonEncoder[RemoteVariableName] =
    implicitly[JsonEncoder[String]].contramap(RemoteVariableName.unwrap(_))
  @nowarn private implicit val templateIdDecoder: JsonDecoder[RemoteVariableName] =
    implicitly[JsonDecoder[String]].map(RemoteVariableName.unsafeMake(_))

  implicit val codec: JsonCodec[GetVariableResponse] = DeriveJsonCodec.gen[GetVariableResponse]
}
