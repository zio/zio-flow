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

import zio._
import zio.aws.core.config.{AwsConfig, CommonAwsConfig}
import zio.aws.dynamodb.DynamoDb
import zio.aws.netty.{NettyClientConfig, NettyHttpClient}
import zio.config.typesafe.TypesafeConfigProvider
import zio.flow.Configuration
import zio.flow.cassandra.{CassandraIndexedStore, CassandraKeyValueStore}
import zio.flow.dynamodb.{DynamoDbIndexedStore, DynamoDbKeyValueStore}
import zio.flow.rocksdb.{RocksDbIndexedStore, RocksDbKeyValueStore}
import zio.flow.runtime.internal.{DefaultOperationExecutor, PersistentExecutor, PersistentState}
import zio.flow.runtime.operation.http.HttpOperationPolicies
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore, serialization}
import zio.flow.server.ServerConfig.{BackendImplementation, SerializationFormat}
import zio.flow.server.flows.FlowsApi
import zio.flow.server.templates.TemplatesApi
import zio.flow.server.templates.service.KVStoreBasedTemplates
import zio.http._
import zio.logging.slf4j.bridge.Slf4jBridge
import zio.metrics.MetricKeyType.Histogram
import zio.metrics.connectors.prometheus
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.metrics.jvm.DefaultJvmMetrics

import java.nio.file.Paths

object Main extends ZIOAppDefault {

  private val healthcheck =
    Http.collect[Request] { case Method.GET -> !! / "healthcheck" =>
      Response(Status.Ok, body = Body.fromString("zio-flow-server is running"))
    }

  private val metrics =
    Http.collectZIO[Request] { case Method.GET -> !! / "metrics" =>
      ZIO.serviceWithZIO[PrometheusPublisher](_.get).map { doc =>
        Response(Status.Ok, body = Body.fromString(doc))
      }
    }

  private def runServer: ZIO[Server with TemplatesApi with FlowsApi with PrometheusPublisher, Throwable, Unit] =
    for {
      templatesApi <- TemplatesApi.endpoint
      flowApi      <- FlowsApi.endpoint
      server        = healthcheck ++ metrics ++ templatesApi ++ flowApi
      _            <- ZIO.logInfo(s"Starting server")
      port         <- Server.install(server)
      _            <- ZIO.logInfo(s"Started on port $port")
      _            <- ZIO.never
    } yield ()

  private def zioConfigProvider(configSource: Option[java.nio.file.Path]): ConfigProvider =
    configSource match {
      case Some(value) => TypesafeConfigProvider.fromHoconFile(value.toFile)
      case None        => TypesafeConfigProvider.fromResourcePath()
    }

  private def dynamoDb(
    commonAwsConfig: CommonAwsConfig,
    awsNettyConfig: NettyClientConfig
  ): ZLayer[Any, Throwable, DynamoDb] =
    ZLayer.make[DynamoDb](
      ZLayer.succeed(commonAwsConfig),
      ZLayer.succeed(awsNettyConfig),
      NettyHttpClient.configured(),
      AwsConfig.configured(),
      DynamoDb.live
    ) @@ zio.aws.core.aspects.callDuration(
      prefix = "zioflow",
      boundaries = Histogram.Boundaries.exponential(0.01, 2, 14)
    )

  private def configured(config: ServerConfig): ZIO[Any, Throwable, Unit] =
    runServer.provide(
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
        case BackendImplementation.DynamoDb =>
          dynamoDb(config.commonAwsConfig, config.awsNettyClientConfig) >>> DynamoDbKeyValueStore.layer
      },
      config.indexedStore match {
        case BackendImplementation.InMemory  => IndexedStore.inMemory
        case BackendImplementation.RocksDb   => RocksDbIndexedStore.layer
        case BackendImplementation.Cassandra => CassandraIndexedStore.layer
        case BackendImplementation.DynamoDb =>
          dynamoDb(config.commonAwsConfig, config.awsNettyClientConfig) >>> DynamoDbIndexedStore.layer
      },
      DurableLog.layer,
      DefaultOperationExecutor.layer,
      HttpOperationPolicies.fromConfig("policies", "http"),
      config.serializationFormat match {
        case SerializationFormat.Json     => ZLayer.succeed(serialization.json)
        case SerializationFormat.Protobuf => ZLayer.succeed(serialization.protobuf)
      },
      PersistentExecutor.make(config.gcPeriod),
      PersistentState.configured(config.persisterConfig),
      Server.configured()
    )

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] =
    for {
      confPath <- System.env("ZIO_FLOW_SERVER_CONFIG").map(_.map(Paths.get(_)))
      _        <- DefaultServices.currentServices.locallyScopedWith(_.add(zioConfigProvider(confPath)))
      config   <- ZIO.config(ServerConfig.config)
      logging = Runtime.removeDefaultLoggers ++ Runtime.addLogger(
                  ZLogger.default.map(println(_)).filterLogLevel(_ >= config.logLevel)
                ) ++ Runtime.setUnhandledErrorLogLevel(LogLevel.Error)
      _ <- logging {
             ZIO.logDebug(s"Loaded server configuration $config") *>
               configured(config)
           }
      _ <- ZIO.never
    } yield ()
}
