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

package zio.flow.server.templates.service

import zio.constraintless.TypeList._
import zio.flow.runtime.{KeyValueStore, Timestamp}
import zio.flow.server.templates.model.{TemplateId, ZFlowTemplate, ZFlowTemplateWithId}
import zio.flow.server.templates.service.KVStoreBasedTemplates.{codecs, namespace}
import zio.schema.codec.BinaryCodecs
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec
import zio.stream.ZStream
import zio.{Clock, ZIO, ZLayer}

import java.io.IOException
import java.nio.charset.StandardCharsets

final case class KVStoreBasedTemplates(kvStore: KeyValueStore) extends Templates {
  def all: ZStream[Any, Throwable, ZFlowTemplateWithId] =
    kvStore.scanAll(namespace).mapZIO { case (rawKey, rawFlowTemplate) =>
      val templateId = TemplateId(new String(rawKey.toArray, StandardCharsets.UTF_8))
      ZIO
        .fromEither(codecs.decode(rawFlowTemplate))
        .mapBoth(
          error => new IOException(s"Can not deserialize template $templateId: $error"),
          flowTemplate => ZFlowTemplateWithId(templateId, flowTemplate)
        )
    }

  def get(templateId: TemplateId): ZIO[Any, Throwable, Option[ZFlowTemplate]] =
    kvStore.getLatest(namespace, templateId.toRaw, None).flatMap {
      case Some(bytes) =>
        ZIO
          .fromEither(codecs.decode(bytes))
          .mapBoth(error => new IOException(s"Failed to deserialize template $templateId: $error"), Some(_))
      case None =>
        ZIO.none
    }

  def put(templateId: TemplateId, flowTemplate: ZFlowTemplate): ZIO[Any, Throwable, Unit] =
    for {
      now <- Clock.nanoTime.map(Timestamp(_))
      _ <- kvStore.put(
             namespace,
             templateId.toRaw,
             codecs.encode(flowTemplate),
             now
           )
    } yield ()

  def delete(templateId: TemplateId): ZIO[Any, Throwable, Unit] =
    kvStore
      .delete(namespace, templateId.toRaw, None)
}

object KVStoreBasedTemplates {
  val layer: ZLayer[KeyValueStore, Nothing, KVStoreBasedTemplates] =
    ZLayer {
      for {
        kvStore <- ZIO.service[KeyValueStore]
      } yield KVStoreBasedTemplates(kvStore)
    }

  private val namespace = "_zflow_workflow_templates"
  private val codecs    = BinaryCodecs.make[ZFlowTemplate :: End]
}
