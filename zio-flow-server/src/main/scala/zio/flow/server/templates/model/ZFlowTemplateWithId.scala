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

package zio.flow.server.templates.model

import zio.schema.{DeriveSchema, Schema}

case class ZFlowTemplateWithId(templateId: TemplateId, template: ZFlowTemplate)

object ZFlowTemplateWithId {
  implicit val schema: Schema[ZFlowTemplateWithId] = DeriveSchema.gen
}
