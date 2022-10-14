/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.server.flows.model

import zio.flow.ZFlow
import zio.flow.server.templates.model.TemplateId
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder}
import zio.json.ast.Json

import scala.annotation.nowarn

sealed trait StartRequest

object StartRequest {
  final case class Flow(flow: ZFlow[Any, Any, Any])                           extends StartRequest
  final case class FlowWithParameter(flow: ZFlow[Any, Any, Any], value: Json) extends StartRequest
  final case class Template(templateId: TemplateId)                           extends StartRequest
  final case class TemplateWithParameter(templateId: TemplateId, value: Json) extends StartRequest

  @nowarn private implicit val zflowEncoder: JsonEncoder[ZFlow[Any, Any, Any]] =
    zio.schema.codec.JsonCodec.jsonEncoder(ZFlow.schemaAny)
  @nowarn private implicit val zflowDecoder: JsonDecoder[ZFlow[Any, Any, Any]] =
    zio.schema.codec.JsonCodec.jsonDecoder(ZFlow.schemaAny)

  @nowarn private implicit val templateIdEncoder: JsonEncoder[TemplateId] =
    implicitly[JsonEncoder[String]].contramap(TemplateId.unwrap(_))
  @nowarn private implicit val templateIdDecoder: JsonDecoder[TemplateId] =
    implicitly[JsonDecoder[String]].map(TemplateId(_))

  implicit val codec: JsonCodec[StartRequest] = DeriveJsonCodec.gen[StartRequest]
}
