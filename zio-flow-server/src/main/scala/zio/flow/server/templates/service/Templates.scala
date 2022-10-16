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

package zio.flow.server.templates.service

import zio._
import zio.flow.server.templates.model.{TemplateId, ZFlowTemplate, ZFlowTemplateWithId}
import zio.stream.ZStream

trait Templates {
  def all: ZStream[Any, Throwable, ZFlowTemplateWithId]
  def get(templateId: TemplateId): ZIO[Any, Throwable, Option[ZFlowTemplate]]
  def put(templateId: TemplateId, flowTemplate: ZFlowTemplate): ZIO[Any, Throwable, Unit]
  def delete(templateId: TemplateId): ZIO[Any, Throwable, Unit]
}

object Templates {
  def all: ZStream[Templates, Throwable, ZFlowTemplateWithId] =
    ZStream.serviceWithStream(_.all)

  def get(templateId: TemplateId): ZIO[Templates, Throwable, Option[ZFlowTemplate]] =
    ZIO.serviceWithZIO(_.get(templateId))

  def put(templateId: TemplateId, flowTemplate: ZFlowTemplate): ZIO[Templates, Throwable, Unit] =
    ZIO.serviceWithZIO(_.put(templateId, flowTemplate))

  def delete(templateId: TemplateId): ZIO[Templates, Throwable, Unit] =
    ZIO.serviceWithZIO(_.delete(templateId))
}
