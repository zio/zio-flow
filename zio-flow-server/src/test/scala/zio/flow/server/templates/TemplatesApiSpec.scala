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

import zio.flow.ZFlow
import zio.flow.runtime.KeyValueStore
import zio.flow.server.common.ApiSpecBase
import zio.flow.server.templates.model.{TemplateId, ZFlowTemplate, ZFlowTemplateWithId, ZFlowTemplates}
import zio.flow.server.templates.service.KVStoreBasedTemplates
import zio.http._
import zio.schema.Schema
import zio.schema.codec.JsonCodec.{JsonDecoder, JsonEncoder}
import zio.test.{TestAspect, assertTrue}
import zio.{ZIO, ZLayer}

object TemplatesApiSpec extends ApiSpecBase {

  private val template1 = ZFlowTemplate(ZFlow.log("Hello world"))
  private val template2 = ZFlowTemplate(ZFlow.input[String].flatMap(ZFlow.log), Schema[String])

  override def spec =
    suite("TemplatesApi")(
      test("get all templates from empty database") {
        for {
          _ <- reset()

          client  <- ZIO.service[Client]
          baseUrl <- ZIO.service[URL]
          response <- client.request(
                        Request.get(
                          url = baseUrl.withPath("/templates")
                        )
                      )
          body <- response.body.asString
        } yield assertTrue(body == """{"entries":[]}""")
      },
      test("put twice, get all") {
        for {
          _ <- reset()

          client  <- ZIO.service[Client]
          baseUrl <- ZIO.service[URL]

          putResponse1 <- client.request(
                            Request.put(
                              url = baseUrl.withPath(s"/templates/t1"),
                              body = Body.fromChunk(
                                JsonEncoder.encode(Schema[ZFlowTemplate], template1)
                              )
                            )
                          )
          putResponse2 <- client.request(
                            Request.put(
                              url = baseUrl.withPath(s"/templates/t2"),
                              body = Body.fromChunk(
                                JsonEncoder.encode(Schema[ZFlowTemplate], template2)
                              )
                            )
                          )

          response <- client.request(
                        Request.get(
                          url = baseUrl.withPath("/templates")
                        )
                      )
          body <- response.body.asString
          decoded <- ZIO.fromEither(
                       JsonDecoder.decode(ZFlowTemplates.schema, body)
                     )
        } yield assertTrue(
          putResponse1.status == Status.Ok,
          putResponse2.status == Status.Ok,
          decoded.entries.size == 2,
          decoded.entries.contains(ZFlowTemplateWithId(TemplateId("t1"), template1)),
          decoded.entries.contains(ZFlowTemplateWithId(TemplateId("t2"), template2))
        )
      },
      test("put twice, delete, get twice") {
        for {
          _ <- reset()

          client  <- ZIO.service[Client]
          baseUrl <- ZIO.service[URL]

          putResponse1 <- client.request(
                            Request.put(
                              url = baseUrl.withPath(s"/templates/t1"),
                              body = Body.fromChunk(
                                JsonEncoder.encode(Schema[ZFlowTemplate], template1)
                              )
                            )
                          )
          putResponse2 <- client.request(
                            Request.put(
                              url = baseUrl.withPath(s"/templates/t2"),
                              body = Body.fromChunk(
                                JsonEncoder.encode(Schema[ZFlowTemplate], template2)
                              )
                            )
                          )

          deleteResponse <- client.request(
                              Request.delete(
                                url = baseUrl.withPath(s"/templates/t2")
                              )
                            )

          getResponse1 <- client.request(
                            Request.get(
                              url = baseUrl.withPath("/templates/t1")
                            )
                          )

          getResponse2 <- client.request(
                            Request.get(
                              url = baseUrl.withPath("/templates/t2")
                            )
                          )

          body <- getResponse1.body.asString
          decoded <- ZIO.fromEither(
                       JsonDecoder.decode(ZFlowTemplate.schema, body)
                     )
        } yield assertTrue(
          putResponse1.status == Status.Ok,
          putResponse2.status == Status.Ok,
          deleteResponse.status == Status.NoContent,
          getResponse1.status == Status.Ok,
          getResponse2.status == Status.NotFound,
          decoded == template1
        )
      }
    ).provideSomeShared[Client](
      KeyValueStore.inMemory,
      KVStoreBasedTemplates.layer,
      TemplatesApi.layer,
      server,
      ZLayer(
        ZIO.fromEither(URL.decode(s"http://localhost:$port"))
      )
    ) @@ TestAspect.sequential @@ TestAspect.flaky @@ TestAspect.withLiveClock

  private def reset() =
    KeyValueStore
      .scanAll("_zflow_workflow_templates")
      .mapZIO(kv => KeyValueStore.delete("_zflow_workflow_templates", kv._1, None))
      .runDrain

  private val port = 8090
  private val server: ZLayer[TemplatesApi, Throwable, Unit] =
    ZLayer.scoped {
      ZIO.service[TemplatesApi].flatMap { api =>
        Server.serve(api.endpoint).provide(Server.defaultWithPort(port)).forkScoped.unit
      }
    }

}
