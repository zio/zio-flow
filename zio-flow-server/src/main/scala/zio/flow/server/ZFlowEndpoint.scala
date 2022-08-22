package zio.flow.server

import zhttp.http.HttpError._
import zhttp.http.Method._
import zhttp.http._
import zio._
import zio.flow._
import zio.flow.server.ZFlowEndpoint.deserializeFlow
import zio.schema.codec.JsonCodec

final class ZFlowEndpoint(flowService: ZFlowService) {
  val endpoint: HttpApp[Any, Nothing] = Http
    .collectZIO[Request] {
      case GET -> !! / "templates" / templateId =>
        flowService
          .getZFlowTemplate(TemplateId(templateId))
          .fold(
            error => Response.fromHttpError(HttpError.InternalServerError(error.getMessage)),
            flow =>
              flow.fold(Response.text(s"Workflow $templateId not found").setStatus(Status.NotFound)) { flow =>
                Response(
                  data = HttpData.fromChunk(JsonCodec.encode(ZFlow.schemaAny)(flow)),
                  headers = Headers(HeaderNames.contentType, HeaderValues.applicationJson)
                )
              }
          )
      case request @ PUT -> !! / "templates" / templateId =>
        for {
          flow <- deserializeFlow(request)
          _    <- flowService.saveZFlowTemplate(TemplateId(templateId), flow)
        } yield Response.ok
      case DELETE -> !! / "templates" / templateId =>
        flowService
          .deleteZFlowTemplate(TemplateId(templateId))
          .fold[Response](
            error => Response.fromHttpError(InternalServerError(error.getMessage, Some(error))),
            _ => Response.status(Status.NoContent)
          )
    }
    .catchAll { error =>
      Http.error(InternalServerError(error.getMessage, Some(error)))
    }
}
object ZFlowEndpoint {
  private def deserializeFlow(request: Request): ZIO[Any, Throwable, ZFlow[Any, Any, Any]] =
    for {
      payload <- request.body
      zFlow <- ZIO
                 .fromEither(jsonToZFlow(payload))
                 // TODO custom error type? ;)
                 .mapError(str => new IllegalArgumentException(str))
    } yield zFlow

  private def jsonToZFlow: Chunk[Byte] => Either[String, ZFlow[Any, Any, Any]] = JsonCodec.decode(ZFlow.schemaAny)

  val layer: ZLayer[ZFlowService, Nothing, ZFlowEndpoint] =
    ZLayer.fromFunction(new ZFlowEndpoint(_))

  val endpoint: ZIO[ZFlowEndpoint, Nothing, HttpApp[Any, Nothing]] = ZIO.serviceWith(_.endpoint)
}
