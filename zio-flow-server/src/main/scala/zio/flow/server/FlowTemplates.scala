package zio.flow.server

import zio._
import zio.flow.internal.{KeyValueStore, Namespaces, Timestamp, ZFlowExecutor}
import zio.flow.{ExecutionEnvironment, FlowId, TemplateId, ZFlow}

import java.io.IOException

final class FlowTemplates(execEnv: ExecutionEnvironment, keyValueStore: KeyValueStore, flowExecutor: ZFlowExecutor) {
  def getZFlowTemplate(templateId: TemplateId): ZIO[Any, Throwable, Option[ZFlow[Any, Any, Any]]] =
    keyValueStore.getLatest(Namespaces.workflowTemplate, templateId.toRaw, None).flatMap {
      case Some(bytes) =>
        ZIO
          .fromEither(execEnv.deserializer.deserialize[ZFlow[Any, Any, Any]](bytes))
          .mapBoth(error => new IllegalStateException(s"Can not deserialize template $templateId: $error"), Some(_))
      case None =>
        ZIO.none
    }

  def saveZFlowTemplate(templateId: TemplateId, flow: ZFlow[Any, Any, Any]): ZIO[Any, IOException, Unit] =
    for {
      now <- Clock.nanoTime.map(Timestamp(_))
      _ <- keyValueStore.put(
             Namespaces.workflowTemplate,
             templateId.toRaw,
             execEnv.serializer.serialize[ZFlow[Any, Any, Any]](flow),
             now
           )
    } yield ()

  def deleteZFlowTemplate(templateId: TemplateId): ZIO[Any, IOException, Unit] =
    keyValueStore
      .delete(Namespaces.workflowTemplate, templateId.toRaw)

  def trigger(templateId: TemplateId): ZIO[Any, Throwable, FlowId] = for {
    flowId <- FlowId.newRandom
    template <-
      getZFlowTemplate(templateId).someOrFail(new IllegalArgumentException(s"Template $templateId is not defined"))
    _ <- flowExecutor.start(flowId, template)
  } yield flowId
}
object FlowTemplates {
  val layer: ZLayer[ExecutionEnvironment with KeyValueStore with ZFlowExecutor, Nothing, FlowTemplates] =
    ZLayer.fromFunction(new FlowTemplates(_, _, _))
}
