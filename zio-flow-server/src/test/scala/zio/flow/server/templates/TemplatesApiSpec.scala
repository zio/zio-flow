package zio.flow.server.templates

import zhttp.http._
import zhttp.service.{ChannelFactory, Client, EventLoopGroup, Server}
import zio.flow.ZFlow
import zio.flow.runtime.KeyValueStore
import zio.flow.server.templates.model.{TemplateId, ZFlowTemplate, ZFlowTemplateWithId, ZFlowTemplates}
import zio.flow.server.templates.service.KVStoreBasedTemplates
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import zio.test.{Spec, TestAspect, TestEnvironment, ZIOSpecDefault, assertTrue}
import zio.{Chunk, Scope, ZIO, ZLayer}

import java.nio.charset.StandardCharsets

object TemplatesApiSpec extends ZIOSpecDefault {

  private val template1 = ZFlowTemplate(ZFlow.log("Hello world"))
  private val template2 = ZFlowTemplate(ZFlow.input[String].flatMap(ZFlow.log), Schema[String])

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("TemplatesApi")(
      test("get all templates from empty database") {
        for {
          _ <- reset()

          client       <- ZIO.service[Client[Any]]
          clientConfig <- ZIO.service[Client.Config]
          baseUrl      <- ZIO.service[URL]
          response <- client.request(
                        Request(
                          method = Method.GET,
                          url = baseUrl.setPath("/templates")
                        ),
                        clientConfig
                      )
          body <- response.data.toByteBuf.map(_.toString(StandardCharsets.UTF_8))
        } yield assertTrue(body == """{"entries":[]}""")
      },
      test("put twice, get all") {
        for {
          _ <- reset()

          client       <- ZIO.service[Client[Any]]
          clientConfig <- ZIO.service[Client.Config]
          baseUrl      <- ZIO.service[URL]

          putResponse1 <- client.request(
                            Request(
                              method = Method.PUT,
                              url = baseUrl.setPath(s"/templates/t1"),
                              data = HttpData.fromChunk(
                                JsonCodec.encode(Schema[ZFlowTemplate])(template1)
                              )
                            ),
                            clientConfig
                          )
          putResponse2 <- client.request(
                            Request(
                              method = Method.PUT,
                              url = baseUrl.setPath(s"/templates/t2"),
                              data = HttpData.fromChunk(
                                JsonCodec.encode(Schema[ZFlowTemplate])(template2)
                              )
                            ),
                            clientConfig
                          )

          response <- client.request(
                        Request(
                          method = Method.GET,
                          url = baseUrl.setPath("/templates")
                        ),
                        clientConfig
                      )
          body <- response.data.toByteBuf.map(_.toString(StandardCharsets.UTF_8))
          decoded <- ZIO.fromEither(
                       JsonCodec.decode(ZFlowTemplates.schema)(Chunk.fromArray(body.getBytes(StandardCharsets.UTF_8)))
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

          client       <- ZIO.service[Client[Any]]
          clientConfig <- ZIO.service[Client.Config]
          baseUrl      <- ZIO.service[URL]

          putResponse1 <- client.request(
                            Request(
                              method = Method.PUT,
                              url = baseUrl.setPath(s"/templates/t1"),
                              data = HttpData.fromChunk(
                                JsonCodec.encode(Schema[ZFlowTemplate])(template1)
                              )
                            ),
                            clientConfig
                          )
          putResponse2 <- client.request(
                            Request(
                              method = Method.PUT,
                              url = baseUrl.setPath(s"/templates/t2"),
                              data = HttpData.fromChunk(
                                JsonCodec.encode(Schema[ZFlowTemplate])(template2)
                              )
                            ),
                            clientConfig
                          )

          deleteResponse <- client.request(
                              Request(
                                method = Method.DELETE,
                                url = baseUrl.setPath(s"/templates/t2")
                              ),
                              clientConfig
                            )

          getResponse1 <- client.request(
                            Request(
                              method = Method.GET,
                              url = baseUrl.setPath("/templates/t1")
                            ),
                            clientConfig
                          )

          getResponse2 <- client.request(
                            Request(
                              method = Method.GET,
                              url = baseUrl.setPath("/templates/t2")
                            ),
                            clientConfig
                          )

          body <- getResponse1.data.toByteBuf.map(_.toString(StandardCharsets.UTF_8))
          decoded <- ZIO.fromEither(
                       JsonCodec.decode(ZFlowTemplate.schema)(Chunk.fromArray(body.getBytes(StandardCharsets.UTF_8)))
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
    ).provideShared(
      KeyValueStore.inMemory,
      KVStoreBasedTemplates.layer,
      TemplatesApi.layer,
      server,
      client
    ) @@ TestAspect.sequential

  private def reset() =
    KeyValueStore
      .scanAll("_zflow_workflow_templates")
      .mapZIO(kv => KeyValueStore.delete("_zflow_workflow_templates", kv._1))
      .runDrain

  private val port = 8090
  private val server: ZLayer[TemplatesApi, Throwable, Unit] =
    ZLayer.scoped {
      ZIO.service[TemplatesApi].flatMap { api =>
        Server.start(port, api.endpoint).forkScoped.unit
      }
    }

  private val client: ZLayer[Any, Throwable, Client[Any] with Client.Config with URL] =
    ZLayer.make[Client[Any] with Client.Config with URL](
      EventLoopGroup.auto(),
      ChannelFactory.auto,
      ZLayer(Client.make[Any]),
      ZLayer(
        ZIO.fromEither(URL.fromString(s"http://localhost:$port"))
      ),
      ZLayer.succeed(Client.Config.empty)
    )
}
