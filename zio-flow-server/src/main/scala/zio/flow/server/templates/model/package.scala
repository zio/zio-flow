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

package zio.flow.server.templates

import zio.Chunk
import zio.prelude.Newtype
import zio.schema.Schema

import java.nio.charset.StandardCharsets

package object model {

  object TemplateId extends Newtype[String] {
    implicit val schema: Schema[TemplateId] = Schema[String].transform(apply(_), unwrap)
  }

  type TemplateId = TemplateId.Type

  implicit class TemplateIdSyntax(val templateId: TemplateId) extends AnyVal {
    def toRaw: Chunk[Byte] = Chunk.fromArray(TemplateId.unwrap(templateId).getBytes(StandardCharsets.UTF_8))
  }

}
