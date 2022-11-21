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

package zio.flow.server

import zhttp.http.Method._
import zhttp.http._
import zhttp.service.Server
import zio._
import zio.flow.Configuration
import zio.flow.runtime.internal.{DefaultOperationExecutor, PersistentExecutor}
import zio.flow.runtime.operation.http.HttpOperationPolicies
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore}
import zio.flow.serialization.{Deserializer, Serializer}
import zio.flow.server.flows.FlowsApi
import zio.flow.server.templates.TemplatesApi
import zio.flow.server.templates.service.KVStoreBasedTemplates
import zio.metrics.connectors.{MetricsConfig, prometheus}
import zio.metrics.connectors.prometheus.PrometheusPublisher

object Main extends ZIOAppDefault {

  private val healthcheck =
    Http.collect[Request] { case GET -> !! / "healthcheck" =>
      Response(Status.Ok, body = Body.fromString("zio-flow-server is running"))
    }

  private val metrics =
    Http.collectZIO[Request] { case GET -> !! / "metrics" =>
      ZIO.serviceWithZIO[PrometheusPublisher](_.get).map { doc =>
        Response(Status.Ok, body = Body.fromString(doc))
      }
    }

  private def runServer: ZIO[TemplatesApi with FlowsApi with PrometheusPublisher, Throwable, Unit] =
    for {
      templatesApi <- TemplatesApi.endpoint
      flowApi      <- FlowsApi.endpoint
      server        = healthcheck ++ metrics ++ templatesApi ++ flowApi
      _            <- Server.start(8090, server)
    } yield ()

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    runServer.provide(
      TemplatesApi.layer,
      FlowsApi.layer,
      ZLayer.succeed(MetricsConfig(5.seconds)),
      prometheus.publisherLayer,
      prometheus.prometheusLayer,
      Configuration.inMemory,
      KeyValueStore.inMemory,
      KVStoreBasedTemplates.layer,
      DurableLog.layer,
      IndexedStore.inMemory,
      DefaultOperationExecutor.layer,
      HttpOperationPolicies.disabled,
      ZLayer.succeed(Serializer.json),
      ZLayer.succeed(Deserializer.json),
      PersistentExecutor.make()
    )
}
