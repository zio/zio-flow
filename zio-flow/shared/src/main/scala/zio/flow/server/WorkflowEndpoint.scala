package zio.flow.server

import zhttp.http.{Http, HttpApp, Method, Request, Response}
import zhttp.http._
import zio.ZIO
import zio.flow.{FlowId, ZFlow}
import zio.flow.internal.ZFlowExecutor
import zio.flow.server.WorkflowEndpoint.jsonToZFlow
import zio.schema.{DynamicValue, Schema}
import zio.schema.codec.JsonCodec

import java.nio.charset.StandardCharsets

class WorkflowEndpoint(pExec: ZFlowExecutor) {

  // Create HTTP route
  val endpoint: HttpApp[Any, Nothing] = Http.collectZIO[Request] {
    // Submit a job, return a UUID
    case req @ Method.POST -> !! / "submit" =>
      val fIdZ = for {
        flow <- deserializeFlow(req)
        fId  <- FlowId.newRandom
        // is orDie smart here?
        _ <- pExec.start(fId, flow).orDie
      } yield fId

      fIdZ.fold(e => Response.text("Ran into error: " + e), ok => Response.text(ok.toString))

    // Poll for a result
    case Method.GET -> !! / "poll" / uuid =>
      val maybeResponse =
        FlowId
          .make(uuid)
          .toZIO
          .mapError(new IllegalArgumentException(_))
          .flatMap(pExec.pollWorkflowDynTyped)
          .flatMap {
            case None =>
              // Job not done yet, nothing to return
              ZIO.succeed(Response.text("Come back later"))
            case Some(r) =>
              // We have a result: serialize and send back.
              // TODO should we have a class that will convey success/failure information?
              r.fold(
                err =>
                  // TODO convey back that it's a failure?
                  JsonCodec.encode(Schema[DynamicValue])(err),
                ok =>
                  // TODO convey back that it's a success?
                  JsonCodec.encode(Schema[DynamicValue])(ok.result)
              ).map(bytes => Response.json(new String(bytes.toArray, StandardCharsets.UTF_8)))
          }

      maybeResponse.fold(
        e => Response.text("Ran into an error while checking for a result: " + e),
        ok => ok
      )
  }

  def deserializeFlow(r: Request) =
    for {
      payload <- r.body
      zFlow <- ZIO
                 .fromEither(jsonToZFlow(payload))
                 // TODO custom error type? ;)
                 .mapError(str => new Exception(str))
    } yield zFlow
}

object WorkflowEndpoint {

  def jsonToZFlow = JsonCodec.decode(ZFlow.schemaAny)

  def make(): ZIO[ZFlowExecutor, Nothing, WorkflowEndpoint] =
    for {
      pExec <- ZIO.environmentWith[ZFlowExecutor](_.get)
    } yield new WorkflowEndpoint(pExec)

  def makeBroken() = new WorkflowEndpoint(null)
}
