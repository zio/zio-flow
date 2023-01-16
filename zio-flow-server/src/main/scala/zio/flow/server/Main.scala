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

package zio.flow.server

import zhttp.http.Method._
import zhttp.http._
import zhttp.service.Server
import zio._
import zio.aws.core.config.{AwsConfig, CommonAwsConfig}
import zio.aws.dynamodb.DynamoDb
import zio.aws.netty.{NettyClientConfig, NettyHttpClient}
import zio.config.typesafe.TypesafeConfigSource
import zio.config.{ConfigDescriptor, ReadError}
import zio.flow.Configuration
import zio.flow.cassandra.{CassandraIndexedStore, CassandraKeyValueStore}
import zio.flow.dynamodb.{DynamoDbIndexedStore, DynamoDbKeyValueStore}
import zio.flow.rocksdb.{RocksDbIndexedStore, RocksDbKeyValueStore}
import zio.flow.runtime.internal.{DefaultOperationExecutor, PersistentExecutor}
import zio.flow.runtime.operation.http.HttpOperationPolicies
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore, serialization}
import zio.flow.server.ServerConfig.{BackendImplementation, SerializationFormat}
import zio.flow.server.flows.FlowsApi
import zio.flow.server.templates.TemplatesApi
import zio.flow.server.templates.service.KVStoreBasedTemplates
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.metrics.MetricKeyType.Histogram
import zio.metrics.connectors.prometheus
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.metrics.jvm.DefaultJvmMetrics

import java.nio.file.Paths

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

  private def runServer(port: Int): ZIO[TemplatesApi with FlowsApi with PrometheusPublisher, Throwable, Unit] =
    for {
      templatesApi <- TemplatesApi.endpoint
      flowApi      <- FlowsApi.endpoint
      server        = healthcheck ++ metrics ++ templatesApi ++ flowApi
      _            <- ZIO.logInfo(s"Starting server on port $port")
      _            <- Server.start(port, server)
      _            <- ZIO.logInfo(s"Started")
    } yield ()

  private def zioConfigSource(configSource: Option[java.nio.file.Path]): zio.config.ConfigSource =
    configSource match {
      case Some(value) => TypesafeConfigSource.fromHoconFile(value.toFile)
      case None        => TypesafeConfigSource.fromResourcePath
    }

  private def loadCommonAwsConfig(configSource: Option[java.nio.file.Path]): IO[ReadError[String], CommonAwsConfig] =
    zio.config.read(
      ConfigDescriptor.nested("aws") {
        zio.aws.core.config.descriptors.commonAwsConfig
      } from zioConfigSource(configSource)
    )

  private def loadNettyClientConfig(
    configSource: Option[java.nio.file.Path]
  ): IO[ReadError[String], NettyClientConfig] =
    zio.config.read(
      ConfigDescriptor.nested("aws-netty") {
        zio.aws.netty.descriptors.nettyClientConfig
      } from zioConfigSource(configSource)
    )

  private def dynamoDb(configSource: Option[java.nio.file.Path]): ZLayer[Any, Throwable, DynamoDb] =
    // TODO: once zio-aws provides support for zio.Config use that
    ZLayer.make[DynamoDb](
      ZLayer(loadCommonAwsConfig(configSource)),
      ZLayer(loadNettyClientConfig(configSource)),
      NettyHttpClient.configured(),
      AwsConfig.configured(),
      DynamoDb.live
    ) @@ zio.aws.core.aspects.callDuration(
      prefix = "zioflow",
      boundaries = Histogram.Boundaries.exponential(0.01, 2, 14)
    )

  private def configured(config: ServerConfig, configSource: Option[java.nio.file.Path]): ZIO[Any, Throwable, Unit] =
    runServer(config.port).provide(
      Slf4jBridge.initialize,
      DefaultJvmMetrics.live.unit,
      TemplatesApi.layer,
      FlowsApi.layer,
      ZLayer.succeed(config.metrics),
      prometheus.publisherLayer,
      prometheus.prometheusLayer,
      Configuration.fromConfig("flow-configuration"),
      KVStoreBasedTemplates.layer,
      config.keyValueStore match {
        case BackendImplementation.InMemory  => KeyValueStore.inMemory
        case BackendImplementation.RocksDb   => RocksDbKeyValueStore.layer
        case BackendImplementation.Cassandra => CassandraKeyValueStore.layer
        case BackendImplementation.DynamoDb  => dynamoDb(configSource) >>> DynamoDbKeyValueStore.layer
      },
      config.indexedStore match {
        case BackendImplementation.InMemory  => IndexedStore.inMemory
        case BackendImplementation.RocksDb   => RocksDbIndexedStore.layer
        case BackendImplementation.Cassandra => CassandraIndexedStore.layer
        case BackendImplementation.DynamoDb  => dynamoDb(configSource) >>> DynamoDbIndexedStore.layer
      },
      DurableLog.layer,
      DefaultOperationExecutor.layer,
      HttpOperationPolicies.fromConfig("policies", "http"),
      config.serializationFormat match {
        case SerializationFormat.Json     => ZLayer.succeed(serialization.json)
        case SerializationFormat.Protobuf => ZLayer.succeed(serialization.protobuf)
      },
      PersistentExecutor.make(config.gcPeriod)
    )

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    for {
      confPath <- System.env("ZIO_FLOW_SERVER_CONFIG").map(_.map(Paths.get(_)))
      _        <- DefaultServices.currentServices.locallyScopedWith(_.add(ServerConfig.fromTypesafe(confPath)))
      config   <- ZIO.config(ServerConfig.config)
      _        <- ZIO.logDebug(s"Loaded server configuration $config")
      _        <- configured(config, confPath)
    } yield ()
}
