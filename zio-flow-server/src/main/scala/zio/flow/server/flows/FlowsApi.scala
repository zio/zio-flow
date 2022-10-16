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

package zio.flow.server.flows

import zhttp.http._
import zio.flow.FlowId
import zio.flow.runtime.{ExecutorError, ZFlowExecutor}
import zio.flow.server.common.{Api, ErrorResponse}
import zio.flow.server.flows.model.{GetAllResponse, PollResponse, StartRequest, StartResponse}
import zio.schema.codec.JsonCodec
import zio.schema.{DynamicValue, Schema}
import zio.{ZIO, ZLayer}

final case class FlowsApi(executor: ZFlowExecutor) extends Api {

  // Create HTTP route
  val endpoint: HttpApp[Any, Nothing] =
    Http
      .collectZIO[Request] {
        case req @ Method.POST -> !! / "flows" =>
          for {
            request <- jsonCodecBody[StartRequest](req)
            flowId  <- FlowId.newRandom
            _ <- request match {
                   case StartRequest.Flow(flow) =>
                     executor.start(flowId, flow).orDieWith(_.toException)
                   case StartRequest.FlowWithParameter(_, _)     => ???
                   case StartRequest.Template(_)                 => ???
                   case StartRequest.TemplateWithParameter(_, _) => ???
                 }
          } yield jsonResponse(StartResponse(flowId))

        case Method.GET -> !! / "flows" =>
          for {
            flows <-
              executor.getAll.runCollect.mapError(failure => new RuntimeException(s"Failed to list flows: $failure"))
          } yield jsonResponse(GetAllResponse(flows.toMap))

        case Method.GET -> !! / "flows" / uuid =>
          FlowId
            .make(uuid)
            .toZIO
            .mapError(new IllegalArgumentException(_))
            .flatMap { flowId =>
              executor.poll(flowId).mapError(_.toException)
            }
            .flatMap {
              case None =>
                ZIO.succeed(
                  PollResponse.Running
                )
              case Some(Left(Left(executorError))) =>
                ZIO.succeed(PollResponse.Died(executorError))
              case Some(Left(Right(failure))) =>
                ZIO
                  .fromEither(JsonCodec.jsonEncoder(Schema[DynamicValue]).toJsonAST(failure))
                  .mapBoth(
                    failure => new RuntimeException(s"Failed to encode failed flow's result: $failure"),
                    json => PollResponse.Failed(json)
                  )
              case Some(Right(success)) =>
                ZIO
                  .fromEither(JsonCodec.jsonEncoder(Schema[DynamicValue]).toJsonAST(success))
                  .mapBoth(
                    failure => new RuntimeException(s"Failed to encode successful flow's result: $failure"),
                    json => PollResponse.Succeeded(json)
                  )
            }
            .map((response: PollResponse) => jsonCodecResponse(response))

        case Method.DELETE -> !! / "flows" / uuid =>
          FlowId
            .make(uuid)
            .toZIO
            .mapError(new IllegalArgumentException(_))
            .flatMap { flowId =>
              executor
                .delete(flowId)
                .as(Response(status = Status.Ok))
                .catchSome { case ExecutorError.InvalidOperationArguments(details) =>
                  ZIO.succeed(Response(status = Status.BadRequest, data = HttpData.fromString(details)))
                }
                .mapError(_.toException)
            }

        case Method.POST -> !! / "flows" / uuid / "pause" =>
          FlowId
            .make(uuid)
            .toZIO
            .mapError(new IllegalArgumentException(_))
            .flatMap { flowId =>
              executor
                .pause(flowId)
                .as(Response(status = Status.Ok))
                .mapError(_.toException)
            }
        case Method.POST -> !! / "flows" / uuid / "resume" =>
          FlowId
            .make(uuid)
            .toZIO
            .mapError(new IllegalArgumentException(_))
            .flatMap { flowId =>
              executor
                .resume(flowId)
                .as(Response(status = Status.Ok))
                .mapError(_.toException)
            }
        case Method.POST -> !! / "flows" / uuid / "abort" =>
          FlowId
            .make(uuid)
            .toZIO
            .mapError(new IllegalArgumentException(_))
            .flatMap { flowId =>
              executor
                .abort(flowId)
                .as(Response(status = Status.Ok))
                .mapError(_.toException)
            }
      }
      .catchAll { error =>
        Http.response(jsonResponse(ErrorResponse(error.getMessage), Status.InternalServerError))
      }
}

object FlowsApi {
  def endpoint: ZIO[FlowsApi, Nothing, HttpApp[Any, Nothing]] = ZIO.serviceWith(_.endpoint)

  val layer: ZLayer[ZFlowExecutor, Nothing, FlowsApi] =
    ZLayer {
      for {
        executor <- ZIO.service[ZFlowExecutor]
      } yield FlowsApi(executor)
    }
}
