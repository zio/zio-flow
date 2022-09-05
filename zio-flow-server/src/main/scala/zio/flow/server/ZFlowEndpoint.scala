package zio.flow.server

import zhttp.http.Method._
import zhttp.http._
import zio._
import zio.flow._
import zio.flow.server.ZFlowEndpoint.{deserializeTemplate, jsonResponse}
import zio.schema.Schema
import zio.schema.codec.JsonCodec

final class ZFlowEndpoint(flowService: FlowTemplates) {
  val endpoint: HttpApp[Any, Nothing] = Http
    .collectZIO[Request] {
      case GET -> !! / "templates" / templateId =>
        flowService
          .getZFlowTemplate(TemplateId(templateId))
          .map { flow =>
            flow.fold(
              jsonResponse(ErrorResponse(s"Workflow $templateId not found"), Status.NotFound)
            ) { flow =>
              jsonResponse(flow)
            }
          }
      case request @ PUT -> !! / "templates" / templateId =>
        for {
          flowTemplate <- deserializeTemplate(request)
          _            <- flowService.saveZFlowTemplate(TemplateId(templateId), flowTemplate)
        } yield Response.ok
      case DELETE -> !! / "templates" / templateId =>
        flowService.deleteZFlowTemplate(TemplateId(templateId)).as(Response.status(Status.NoContent))
      case POST -> !! / "templates" / templateId / "trigger" =>
        flowService
          .trigger(TemplateId(templateId))
          .map(flowId => jsonResponse(ZFlowTriggered(flowId)))
    }
    .catchAll { error =>
      Http.response(jsonResponse(ErrorResponse(error.getMessage), Status.InternalServerError))
    }
}
object ZFlowEndpoint {
  private def deserializeTemplate(request: Request): ZIO[Any, Throwable, ZFlowTemplate] =
    for {
      payload <- request.body
      zFlow <- ZIO
                 .fromEither(jsonToZFlowTemplate(payload))
                 // TODO custom error type? ;)
                 .mapError(str => new IllegalArgumentException(str))
    } yield zFlow

  private def jsonToZFlowTemplate: Chunk[Byte] => Either[String, ZFlowTemplate] = JsonCodec.decode(ZFlowTemplate.schema)

  val layer: ZLayer[FlowTemplates, Nothing, ZFlowEndpoint] =
    ZLayer.fromFunction(new ZFlowEndpoint(_))

  val endpoint: ZIO[ZFlowEndpoint, Nothing, HttpApp[Any, Nothing]] = ZIO.serviceWith(_.endpoint)

  private[server] def jsonResponse[T](body: T, status: Status = Status.Ok)(implicit schema: Schema[T]): Response =
    Response(
      data = HttpData.fromChunk(JsonCodec.encode(schema)(body)),
      headers = Headers(HeaderNames.contentType, HeaderValues.applicationJson),
      status = status
    )
}
