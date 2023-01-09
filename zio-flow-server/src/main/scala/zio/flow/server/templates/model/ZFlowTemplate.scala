/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

package zio.flow.server.templates.model

import zio.flow.ZFlow
import zio.flow.serialization.FlowSchemaAst
import zio.schema.{DeriveSchema, Schema}

final case class ZFlowTemplate private[model] (flow: ZFlow[Any, Any, Any], inputSchema: Option[FlowSchemaAst])
object ZFlowTemplate {
  def apply(flow: ZFlow[Any, Any, Any]): ZFlowTemplate =
    ZFlowTemplate(flow, None)

  def apply[R](flow: ZFlow[R, Any, Any], schema: Schema[R]): ZFlowTemplate =
    ZFlowTemplate(flow.asInstanceOf[ZFlow[Any, Any, Any]], Some(FlowSchemaAst.fromSchema(schema)))

  implicit val schema: Schema[ZFlowTemplate] = DeriveSchema.gen
}
