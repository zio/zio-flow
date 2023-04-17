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

import zio._
import zio.flow.server.common.{Api, ErrorResponse}
import zio.flow.server.templates.model.{TemplateId, ZFlowTemplate, ZFlowTemplates}
import zio.flow.server.templates.service.Templates
import zio.http._

final case class TemplatesApi(templates: Templates) extends Api {

  val endpoint: App[Any] =
    Http
      .collectZIO[Request] {
        case Method.GET -> !! / "templates" =>
          templates.all.runCollect
            .map(flowTemplates => jsonResponse(ZFlowTemplates(flowTemplates)))

        case Method.GET -> !! / "templates" / templateId =>
          templates
            .get(TemplateId(templateId))
            .map { flow =>
              flow.fold(
                jsonResponse(ErrorResponse(s"Workflow $templateId not found"), Status.NotFound)
              ) { flow =>
                jsonResponse(flow)
              }
            }

        case request @ Method.PUT -> !! / "templates" / templateId =>
          for {
            flowTemplate <- jsonBody[ZFlowTemplate](request)
            _            <- templates.put(TemplateId(templateId), flowTemplate)
          } yield Response.ok

        case Method.DELETE -> !! / "templates" / templateId =>
          templates.delete(TemplateId(templateId)).as(Response.status(Status.NoContent))

      }
      .mapError { error =>
        jsonResponse(ErrorResponse(error.getMessage), Status.InternalServerError)
      }
}
object TemplatesApi {
  def endpoint: ZIO[TemplatesApi, Nothing, App[Any]] = ZIO.serviceWith(_.endpoint)

  val layer: ZLayer[Templates, Nothing, TemplatesApi] =
    ZLayer {
      for {
        templates <- ZIO.service[Templates]
      } yield TemplatesApi(templates)
    }
}
