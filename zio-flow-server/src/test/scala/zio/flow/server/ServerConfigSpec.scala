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

import zio.flow.cassandra.CassandraConfig
import zio.flow.rocksdb.RocksDbConfig
import zio.flow.runtime.operation.http.HttpOperationPolicies
import zio.flow.server.ServerConfig.{BackendImplementation, SerializationFormat}
import zio.flow.{ConfigKey, Configuration}
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}
import zio.{DefaultServices, Scope, ZIO, durationInt}

import java.net.InetSocketAddress
import java.nio.file.Paths

object ServerConfigSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("ServerConfig")(
      test("example HOCON config is loadable") {
        val hocon =
          """
            |port = 8888
            |key-value-store = "rocksdb"
            |indexed-store = "cassandra"
            |metrics {
            |  interval = 10s
            |}
            |serialization-format = "json"
            |gc-period = 1m
            |
            |rocksdb-key-value-store {
            |  path = "/tmp/zio-flow.db"
            |}
            |
            |rocksdb-indexed-store {
            |  path = "/tmp/zio-flow.db"
            |}
            |
            |cassandra-key-value-store {
            |  contact-points = ["127.0.0.1:1111", "1.2.3.4:2222"]
            |  keyspace = "zflow_kv"
            |  local-datacenter = "datacenter1"
            |}
            |
            |cassandra-indexed-store {
            |  contact-points = ["127.0.0.1:8080", "1.2.3.4:9090"]
            |  keyspace = "zflow_ix"
            |  local-datacenter = "datacenter1"
            |}
            |
            |flow-configuration {
            |  TWILIO_ACCOUNT_SID = "AC123"
            |  TWILIO_AUTH_TOKEN = "abc123"
            |}
            |
            |policies {
            |  http {
            |    default {
            |      max-parallel-request-count = 1024
            |      retry-policies = [
            |        {
            |          condition = "for-5xx"
            |          retry-policy {
            |            fail-after {
            |              elapsed-time = 20s
            |            }
            |            repetition {
            |              fixed = 1s
            |            }
            |            jitter = true
            |          }
            |          break-circuit = true
            |        },
            |        {
            |          condition.for-specific-status = 429
            |          retry-policy {
            |            fail-after {
            |              number-of-retries = 5
            |            }
            |            repetition {
            |              exponential {
            |                base = 1s
            |                factor = 2.0
            |                max = 10s
            |              }
            |            }
            |            jitter = true
            |          }
            |          break-circuit = false
            |        },
            |        {
            |          condition.or {
            |            first = "for-4xx"
            |            second = "open-circuit-breaker"
            |          }
            |          retry-policy {
            |            fail-after {
            |              number-of-retries = 5
            |            }
            |            repetition {
            |              exponential {
            |                base = 1s
            |                factor = 2.0
            |                max = 10s
            |              }
            |            }
            |            jitter = true
            |          }
            |          break-circuit = true
            |        }
            |      ]
            |      timeout = 1m
            |    }
            |
            |    per-host {
            |      "example.com" = {
            |        max-parallel-request-count = 16
            |        host-override = "custom.example.com"
            |        retry-policies = []
            |        circuit-breaker-policy {
            |          fail-after {
            |            number-of-retries = 10
            |          }
            |          repetition {
            |            fixed = 1s
            |          }
            |          jitter = false
            |        }
            |        timeout = 5m
            |      }
            |    }
            |  }
            |}
            |""".stripMargin

        for {
          _ <- DefaultServices.currentServices.locallyScopedWith(_.add(ServerConfig.fromTypesafeString(hocon)))
          result <- {
                      for {
                        serverConfig         <- ZIO.config(ServerConfig.config)
                        rocksDbKvStoreConfig <- ZIO.config(RocksDbConfig.config.nested("rocksdb-key-value-store"))
                        rocksDbIxStoreConfig <- ZIO.config(RocksDbConfig.config.nested("rocksdb-indexed-store"))
                        cassandraDbKvStoreConfig <-
                          ZIO.config(CassandraConfig.config.nested("cassandra-key-value-store"))
                        cassandraDbIxStoreConfig <- ZIO.config(CassandraConfig.config.nested("cassandra-indexed-store"))
                        configuration            <- ZIO.service[Configuration]
                        accountSid               <- configuration.get[String](ConfigKey("TWILIO_ACCOUNT_SID"))
                        authToken                <- configuration.get[String](ConfigKey("TWILIO_AUTH_TOKEN"))
                        policies                 <- ZIO.service[HttpOperationPolicies]
                      } yield assertTrue(
                        serverConfig.serializationFormat == SerializationFormat.Json,
                        serverConfig.indexedStore == BackendImplementation.Cassandra,
                        serverConfig.keyValueStore == BackendImplementation.RocksDb,
                        serverConfig.port == 8888,
                        serverConfig.metrics.interval == 10.seconds,
                        serverConfig.gcPeriod == 1.minute,
                        rocksDbKvStoreConfig.path == Paths.get("/tmp/zio-flow.db"),
                        rocksDbIxStoreConfig.path == Paths.get("/tmp/zio-flow.db"),
                        cassandraDbKvStoreConfig.contactPoints == List(
                          new InetSocketAddress("127.0.0.1", 1111),
                          new InetSocketAddress("1.2.3.4", 2222)
                        ),
                        cassandraDbKvStoreConfig.keyspace == "zflow_kv",
                        cassandraDbKvStoreConfig.localDatacenter == "datacenter1",
                        cassandraDbIxStoreConfig.contactPoints == List(
                          new InetSocketAddress("127.0.0.1", 8080),
                          new InetSocketAddress("1.2.3.4", 9090)
                        ),
                        cassandraDbIxStoreConfig.keyspace == "zflow_ix",
                        cassandraDbIxStoreConfig.localDatacenter == "datacenter1",
                        accountSid == Some("AC123"),
                        authToken == Some("abc123"),
                        policies.policyForHost("something").maxParallelRequestCount == 1024,
                        policies.policyForHost("example.com").maxParallelRequestCount == 16,
                        policies.policyForHost("example.com").hostOverride == Some("custom.example.com"),
                        policies.policyForHost("example.com").circuitBreakerPolicy.isDefined,
                        policies.policyForHost("something").circuitBreakerPolicy.isEmpty,
                        policies.policyForHost("something").retryPolicies.size == 3
                      )
                    }.provide(
                      Configuration.fromConfig("flow-configuration"),
                      HttpOperationPolicies.fromConfig("policies", "http")
                    )
        } yield result
      }
    )
}
