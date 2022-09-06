package zio.flow.server

import zio._
import zio.flow.internal.{KeyValueStore, Namespaces, Timestamp, ZFlowExecutor}
import zio.flow.{FlowId, TemplateId, ZFlowTemplate}
import zio.schema.codec.JsonCodec

import java.io.IOException

final class FlowTemplates(keyValueStore: KeyValueStore, flowExecutor: ZFlowExecutor) {
  def getZFlowTemplate(templateId: TemplateId): ZIO[Any, IOException, Option[ZFlowTemplate]] =
    keyValueStore.getLatest(Namespaces.workflowTemplate, templateId.toRaw, None).flatMap {
      case Some(bytes) =>
        ZIO
          .fromEither(JsonCodec.decode(ZFlowTemplate.schema)(bytes))
          .mapBoth(error => new IOException(s"Failed to deserialize template $templateId: $error"), Some(_))
      case None =>
        ZIO.none
    }

  def saveZFlowTemplate(templateId: TemplateId, flowTemplate: ZFlowTemplate): ZIO[Any, IOException, Unit] =
    for {
      now <- Clock.nanoTime.map(Timestamp(_))
      _ <- keyValueStore.put(
             Namespaces.workflowTemplate,
             templateId.toRaw,
             JsonCodec.encode(ZFlowTemplate.schema)(flowTemplate),
             now
           )
    } yield ()

  def deleteZFlowTemplate(templateId: TemplateId): ZIO[Any, IOException, Unit] =
    keyValueStore
      .delete(Namespaces.workflowTemplate, templateId.toRaw)

  def trigger(templateId: TemplateId): ZIO[Any, Throwable, FlowId] = for {
    flowId <- FlowId.newRandom
    flowTemplate <-
      getZFlowTemplate(templateId).someOrFail(new IllegalArgumentException(s"Template $templateId is not defined"))
    _ <- flowExecutor.start(flowId, flowTemplate.template)
  } yield flowId
}
object FlowTemplates {
  val layer: ZLayer[KeyValueStore with ZFlowExecutor, Nothing, FlowTemplates] =
    ZLayer.fromFunction(new FlowTemplates(_, _))
}
