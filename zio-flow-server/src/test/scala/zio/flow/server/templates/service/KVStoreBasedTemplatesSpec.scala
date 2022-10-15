package zio.flow.server.templates.service

import zio.Scope
import zio.flow.ZFlow
import zio.flow.runtime.KeyValueStore
import zio.flow.server.templates.model.{TemplateId, ZFlowTemplate, ZFlowTemplateWithId}
import zio.schema.Schema
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}

object KVStoreBasedTemplatesSpec extends ZIOSpecDefault {

  private val template1 = ZFlowTemplate(ZFlow.log("Hello world"))
  private val template2 = ZFlowTemplate(ZFlow.input[String].flatMap(ZFlow.log), Schema[String])

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("KVStoreBasedTemplates")(
      test("put and get") {
        for {
          _  <- Templates.put(TemplateId("t1"), template1)
          _  <- Templates.put(TemplateId("t2"), template2)
          t1 <- Templates.get(TemplateId("t1"))
          t2 <- Templates.get(TemplateId("t2"))
        } yield assertTrue(
          t1 == Some(template1),
          t2 == Some(template2)
        )
      },
      test("put and all") {
        for {
          _  <- Templates.put(TemplateId("t1"), template1)
          _  <- Templates.put(TemplateId("t2"), template2)
          ts <- Templates.all.runCollect
        } yield assertTrue(
          ts.size == 2,
          ts.contains(ZFlowTemplateWithId(TemplateId("t1"), template1)),
          ts.contains(ZFlowTemplateWithId(TemplateId("t2"), template2))
        )
      },
      test("put, delete and get") {
        for {
          _  <- Templates.put(TemplateId("t1"), template1)
          _  <- Templates.put(TemplateId("t2"), template2)
          _  <- Templates.delete(TemplateId("t1"))
          t1 <- Templates.get(TemplateId("t1"))
          t2 <- Templates.get(TemplateId("t2"))
        } yield assertTrue(
          t1 == None,
          t2 == Some(template2)
        )
      }
    ).provide(
      KeyValueStore.inMemory,
      KVStoreBasedTemplates.layer
    )
}
