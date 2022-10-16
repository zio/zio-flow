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

import zhttp.http.{HttpData, Method, Request, Response, Status, URL}
import zhttp.service.{Client, Server}
import zio.flow.runtime.internal.PersistentExecutor
import zio.flow.runtime.{DurablePromise, ExecutorError, FlowStatus, ZFlowExecutor}
import zio.flow.server.common.ApiSpecBase
import zio.flow.server.flows.model.{GetAllResponse, PollResponse, StartRequest, StartResponse}
import zio.flow.{FlowId, PromiseId, RemoteVariableName, ZFlow}
import zio.json.ast.Json
import zio.schema.codec.JsonCodec
import zio.schema.{DynamicValue, Schema}
import zio.stream.ZStream
import zio.test.{Spec, TestAspect, TestEnvironment, assertTrue}
import zio.{Chunk, IO, Ref, Scope, ULayer, ZIO, ZLayer}

import java.nio.charset.StandardCharsets

object FlowsApiSpec extends ApiSpecBase {
  private val flow1 = ZFlow.succeed(11)

  override def spec: Spec[Client[Any] with Client.Config with TestEnvironment with Scope, Any] =
    suite("FlowsApi")(
      suite("start and poll")(
        test("send flow without parameter and wait for result") {
          for {
            _            <- reset()
            client       <- ZIO.service[Client[Any]]
            clientConfig <- ZIO.service[Client.Config]
            baseUrl      <- ZIO.service[URL]
            response <- client.request(
                          Request(
                            method = Method.POST,
                            url = baseUrl.setPath("/flows"),
                            data = HttpData.fromCharSequence(
                              StartRequest.codec.encodeJson(
                                StartRequest.Flow(flow1),
                                None
                              )
                            )
                          ),
                          clientConfig
                        )
            startResponse <- decodeStartResponse(response)
            started       <- getStarted

            pollResponse1 <-
              client.request(
                Request(method = Method.GET, url = baseUrl.setPath(s"/flows/${FlowId.unwrap(startResponse.flowId)}")),
                clientConfig
              )
            pollResult1 <- decodePollResponse(pollResponse1)

            _ <- addPollHandler(
                   startResponse.flowId,
                   PollHandler(1, Right(DynamicValue.fromSchemaAndValue(Schema[String], "hello")))
                 )

            pollResponse2 <-
              client.request(
                Request(method = Method.GET, url = baseUrl.setPath(s"/flows/${FlowId.unwrap(startResponse.flowId)}")),
                clientConfig
              )
            pollResult2 <- decodePollResponse(pollResponse2)

            pollResponse3 <-
              client.request(
                Request(method = Method.GET, url = baseUrl.setPath(s"/flows/${FlowId.unwrap(startResponse.flowId)}")),
                clientConfig
              )
            pollResult3 <- decodePollResponse(pollResponse3)
          } yield assertTrue(
            started.size == 1,
            response.status == Status.Ok,
            started.contains(startResponse.flowId),
            started(startResponse.flowId) == flow1,
            pollResult1 == PollResponse.Running,
            pollResult2 == PollResponse.Running,
            pollResult3 == PollResponse.Succeeded(Json.Obj("String" -> Json.Str("hello")))
          )
        },
        test("send flow without parameter and wait for failed result") {
          for {
            _            <- reset()
            client       <- ZIO.service[Client[Any]]
            clientConfig <- ZIO.service[Client.Config]
            baseUrl      <- ZIO.service[URL]
            response <- client.request(
                          Request(
                            method = Method.POST,
                            url = baseUrl.setPath("/flows"),
                            data = HttpData.fromCharSequence(
                              StartRequest.codec.encodeJson(
                                StartRequest.Flow(flow1),
                                None
                              )
                            )
                          ),
                          clientConfig
                        )
            startResponse <- decodeStartResponse(response)
            started       <- getStarted

            _ <- addPollHandler(
                   startResponse.flowId,
                   PollHandler(0, Left(Right(DynamicValue.fromSchemaAndValue(Schema[String], "hello"))))
                 )

            pollResponse <-
              client.request(
                Request(method = Method.GET, url = baseUrl.setPath(s"/flows/${FlowId.unwrap(startResponse.flowId)}")),
                clientConfig
              )
            pollResult <- decodePollResponse(pollResponse)
          } yield assertTrue(
            started.size == 1,
            response.status == Status.Ok,
            started.contains(startResponse.flowId),
            started(startResponse.flowId) == flow1,
            pollResult == PollResponse.Failed(Json.Obj("String" -> Json.Str("hello")))
          )
        },
        test("send flow without parameter and wait for crashed result") {
          for {
            _            <- reset()
            client       <- ZIO.service[Client[Any]]
            clientConfig <- ZIO.service[Client.Config]
            baseUrl      <- ZIO.service[URL]
            response <- client.request(
                          Request(
                            method = Method.POST,
                            url = baseUrl.setPath("/flows"),
                            data = HttpData.fromCharSequence(
                              StartRequest.codec.encodeJson(
                                StartRequest.Flow(flow1),
                                None
                              )
                            )
                          ),
                          clientConfig
                        )
            startResponse <- decodeStartResponse(response)
            started       <- getStarted

            _ <- addPollHandler(
                   startResponse.flowId,
                   PollHandler(0, Left(Left(ExecutorError.MissingVariable(RemoteVariableName("x"), "y"))))
                 )

            pollResponse <-
              client.request(
                Request(method = Method.GET, url = baseUrl.setPath(s"/flows/${FlowId.unwrap(startResponse.flowId)}")),
                clientConfig
              )
            pollResult <- decodePollResponse(pollResponse)
          } yield assertTrue(
            started.size == 1,
            response.status == Status.Ok,
            started.contains(startResponse.flowId),
            started(startResponse.flowId) == flow1,
            pollResult == PollResponse.Died(ExecutorError.MissingVariable(RemoteVariableName("x"), "y"))
          )
        }
      ),
      suite("delete")(
        test("delete running flow") {
          for {
            _            <- reset()
            client       <- ZIO.service[Client[Any]]
            clientConfig <- ZIO.service[Client.Config]
            baseUrl      <- ZIO.service[URL]
            response <- client.request(
                          Request(
                            method = Method.POST,
                            url = baseUrl.setPath("/flows"),
                            data = HttpData.fromCharSequence(
                              StartRequest.codec.encodeJson(
                                StartRequest.Flow(flow1),
                                None
                              )
                            )
                          ),
                          clientConfig
                        )
            startResponse <- decodeStartResponse(response)
            started       <- getStarted

            _ <- addPollHandler(
                   startResponse.flowId,
                   PollHandler(0, Left(Right(DynamicValue.fromSchemaAndValue(Schema[String], "hello"))))
                 )

            deleteResponse <-
              client.request(
                Request(
                  method = Method.DELETE,
                  url = baseUrl.setPath(s"/flows/${FlowId.unwrap(startResponse.flowId)}")
                ),
                clientConfig
              )
          } yield assertTrue(
            started.size == 1,
            response.status == Status.Ok,
            started.contains(startResponse.flowId),
            started(startResponse.flowId) == flow1,
            deleteResponse.status == Status.BadRequest
          )
        },
        test("delete finished flow") {
          for {
            _            <- reset()
            client       <- ZIO.service[Client[Any]]
            clientConfig <- ZIO.service[Client.Config]
            baseUrl      <- ZIO.service[URL]

            flowId <- FlowId.newRandom
            deleteResponse <-
              client.request(
                Request(method = Method.DELETE, url = baseUrl.setPath(s"/flows/${FlowId.unwrap(flowId)}")),
                clientConfig
              )
          } yield assertTrue(
            deleteResponse.status == Status.Ok
          )
        }
      ),
      suite("get all")(
        test("get all existing flows") {
          for {
            _            <- reset()
            client       <- ZIO.service[Client[Any]]
            clientConfig <- ZIO.service[Client.Config]
            baseUrl      <- ZIO.service[URL]
            response <- client.request(
                          Request(
                            method = Method.POST,
                            url = baseUrl.setPath("/flows"),
                            data = HttpData.fromCharSequence(
                              StartRequest.codec.encodeJson(
                                StartRequest.Flow(flow1),
                                None
                              )
                            )
                          ),
                          clientConfig
                        )
            _       <- decodeStartResponse(response)
            started <- getStarted

            response <- client.request(
                          Request(method = Method.GET, url = baseUrl.setPath("/flows")),
                          clientConfig
                        )
            result <- decodeGetAllResponse(response)
          } yield assertTrue(
            result.flows.keySet == started.keySet,
            response.status == Status.Ok
          )
        }
      )
    ).provideSomeShared[Client[Any] with Client.Config](
      FlowsApi.layer,
      server,
      ZLayer(
        ZIO.fromEither(URL.fromString(s"http://localhost:$port"))
      ),
      executorMock
    ) @@ TestAspect.sequential

  private def decodeStartResponse(response: Response): ZIO[Any, Serializable, StartResponse] =
    for {
      body <- response.data.toByteBuf.map(_.toString(StandardCharsets.UTF_8))
      result <-
        ZIO.fromEither(
          JsonCodec.decode(StartResponse.schema)(Chunk.fromArray(body.getBytes(StandardCharsets.UTF_8)))
        )
    } yield result

  private def decodeGetAllResponse(response: Response): ZIO[Any, Serializable, GetAllResponse] =
    for {
      body <- response.data.toByteBuf.map(_.toString(StandardCharsets.UTF_8))
      result <-
        ZIO.fromEither(
          JsonCodec.decode(GetAllResponse.schema)(Chunk.fromArray(body.getBytes(StandardCharsets.UTF_8)))
        )
    } yield result

  private def decodePollResponse(response: Response): ZIO[Any, Serializable, PollResponse] =
    for {
      body <- response.data.toByteBuf.map(_.toString(StandardCharsets.UTF_8))
      result <-
        ZIO.fromEither(PollResponse.codec.decodeJson(body))
    } yield result

  private val port = 8091
  private val server: ZLayer[FlowsApi, Throwable, Unit] =
    ZLayer.scoped {
      ZIO.service[FlowsApi].flatMap { api =>
        Server.start(port, api.endpoint).forkScoped.unit
      }
    }

  private def reset(): ZIO[MockedExecutor, Nothing, Unit] =
    for {
      mock <- ZIO.service[MockedExecutor]
      _    <- mock.started.set(Map.empty)
      _    <- mock.pollHandlers.set(Map.empty)
    } yield ()

  private case class PollHandler(
    after: Int,
    result: Either[Either[ExecutorError, DynamicValue], DynamicValue]
  )

  private class MockedExecutor(
    val started: Ref[Map[FlowId, ZFlow[Any, Any, Any]]],
    val pollHandlers: Ref[Map[FlowId, PollHandler]]
  ) extends ZFlowExecutor {
    override def run[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): IO[E, A] = ???

    override def start[E, A](
      id: FlowId,
      flow: ZFlow[Any, E, A]
    ): ZIO[Any, ExecutorError, DurablePromise[Either[ExecutorError, DynamicValue], PersistentExecutor.FlowResult]] =
      started.update(m => m.updated(id, flow)).as(DurablePromise.make(PromiseId(FlowId.unwrap(id))))

    override def poll(
      id: FlowId
    ): ZIO[Any, ExecutorError, Option[Either[Either[ExecutorError, DynamicValue], DynamicValue]]] =
      pollHandlers.modify { handlers =>
        handlers.get(id) match {
          case Some(handler) if handler.after > 0 =>
            (None, handlers.updated(id, handler.copy(after = handler.after - 1)))
          case Some(handler) =>
            (Some(handler.result), handlers.removed(id))
          case None => (None, handlers)
        }
      }

    override def restartAll(): ZIO[Any, ExecutorError, Unit] = ZIO.unit

    override def forceGarbageCollection(): ZIO[Any, Nothing, Unit] = ZIO.unit

    override def delete(id: FlowId): ZIO[Any, ExecutorError, Unit] =
      started.get.map(_.contains(id)).flatMap {
        case true  => ZIO.fail(ExecutorError.InvalidOperationArguments("flow is running"))
        case false => ZIO.unit
      }
    override def pause(id: FlowId): ZIO[Any, ExecutorError, Unit]  = ???
    override def resume(id: FlowId): ZIO[Any, ExecutorError, Unit] = ???
    override def abort(id: FlowId): ZIO[Any, ExecutorError, Unit]  = ???

    override def getAll: ZStream[Any, ExecutorError, (FlowId, FlowStatus)] =
      ZStream
        .fromZIO(started.get)
        .map(m => Chunk.fromIterable(m.keys))
        .flattenChunks
        .map(id => (id, FlowStatus.Running))
  }

  private def getStarted: ZIO[MockedExecutor, Unit, Map[FlowId, ZFlow[Any, Any, Any]]] =
    ZIO.serviceWithZIO(_.started.get)

  private def addPollHandler(flowId: FlowId, pollHandler: PollHandler): ZIO[MockedExecutor, Nothing, Unit] =
    ZIO.serviceWithZIO(_.pollHandlers.update(_.updated(flowId, pollHandler)))

  private val executorMock: ULayer[ZFlowExecutor with MockedExecutor] =
    ZLayer {
      for {
        started      <- Ref.make(Map.empty[FlowId, ZFlow[Any, Any, Any]])
        pollHandlers <- Ref.make(Map.empty[FlowId, PollHandler])
      } yield new MockedExecutor(started, pollHandlers)
    }
}
