package zio.flow.server

import zio._
import zio.flow.internal.{KeyValueStore, Namespaces, Timestamp, ZFlowExecutor}
import zio.flow.{ExecutionEnvironment, FlowId, TemplateId, ZFlowTemplate}

import java.io.IOException

final class FlowTemplates(execEnv: ExecutionEnvironment, keyValueStore: KeyValueStore, flowExecutor: ZFlowExecutor) {
  def getZFlowTemplate(templateId: TemplateId): ZIO[Any, Throwable, Option[ZFlowTemplate]] =
    keyValueStore.getLatest(Namespaces.workflowTemplate, templateId.toRaw, None).flatMap {
      case Some(bytes) =>
        ZIO
          .fromEither(execEnv.deserializer.deserialize[ZFlowTemplate](bytes))
          .mapBoth(error => new IllegalStateException(s"Can not deserialize template $templateId: $error"), Some(_))
      case None =>
        ZIO.none
    }

  def saveZFlowTemplate(templateId: TemplateId, flowTemplate: ZFlowTemplate): ZIO[Any, IOException, Unit] =
    for {
      now <- Clock.nanoTime.map(Timestamp(_))
      _ <- keyValueStore.put(
             Namespaces.workflowTemplate,
             templateId.toRaw,
             execEnv.serializer.serialize[ZFlowTemplate](flowTemplate),
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
  val layer: ZLayer[ExecutionEnvironment with KeyValueStore with ZFlowExecutor, Nothing, FlowTemplates] =
    ZLayer.fromFunction(new FlowTemplates(_, _, _))
}
