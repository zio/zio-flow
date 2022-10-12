package zio.flow.server

import zio._
import zio.flow.internal.Namespaces
import zio.flow.runtime.{KeyValueStore, Timestamp}
import zio.flow.{FlowId, TemplateId, ZFlowExecutor, ZFlowTemplate}
import zio.schema.codec.JsonCodec
import zio.stream.ZStream

import java.io.IOException
import java.nio.charset.StandardCharsets

final class FlowTemplates(keyValueStore: KeyValueStore, flowExecutor: ZFlowExecutor) {
  def getZFlowTemplates(): ZStream[Any, Throwable, (flow.TemplateId.Type, ZFlowTemplate)] =
    keyValueStore.scanAll(Namespaces.workflowTemplate).mapZIO { case (rawKey, rawFlowTemplate) =>
      val templateId = TemplateId(new String(rawKey.toArray, StandardCharsets.UTF_8))
      ZIO
        .fromEither(JsonCodec.decode(ZFlowTemplate.schema)(rawFlowTemplate))
        .mapBoth(
          error => new IOException(s"Can not deserialize template $templateId: $error"),
          flowTemplate => templateId -> flowTemplate
        )
    }

  def getZFlowTemplate(templateId: TemplateId): ZIO[Any, Throwable, Option[ZFlowTemplate]] =
    keyValueStore.getLatest(Namespaces.workflowTemplate, templateId.toRaw, None).flatMap {
      case Some(bytes) =>
        ZIO
          .fromEither(JsonCodec.decode(ZFlowTemplate.schema)(bytes))
          .mapBoth(error => new IOException(s"Failed to deserialize template $templateId: $error"), Some(_))
      case None =>
        ZIO.none
    }

  def saveZFlowTemplate(templateId: TemplateId, flowTemplate: ZFlowTemplate): ZIO[Any, Throwable, Unit] =
    for {
      now <- Clock.nanoTime.map(Timestamp(_))
      _ <- keyValueStore.put(
             Namespaces.workflowTemplate,
             templateId.toRaw,
             JsonCodec.encode(ZFlowTemplate.schema)(flowTemplate),
             now
           )
    } yield ()

  def deleteZFlowTemplate(templateId: TemplateId): ZIO[Any, Throwable, Unit] =
    keyValueStore
      .delete(Namespaces.workflowTemplate, templateId.toRaw)

  def trigger(templateId: TemplateId): ZIO[Any, Throwable, FlowId] = for {
    flowId <- FlowId.newRandom
    flowTemplate <-
      getZFlowTemplate(templateId).someOrFail(new IllegalArgumentException(s"Template $templateId is not defined"))
    _ <- flowExecutor.start(flowId, flowTemplate.template).mapError(_.toException)
  } yield flowId
}
object FlowTemplates {
  val layer: ZLayer[KeyValueStore with ZFlowExecutor, Nothing, FlowTemplates] =
    ZLayer.fromFunction(new FlowTemplates(_, _))
}
