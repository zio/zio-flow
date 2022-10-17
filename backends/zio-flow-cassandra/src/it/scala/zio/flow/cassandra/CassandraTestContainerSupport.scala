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

package zio.flow.cassandra

import com.datastax.oss.driver.api.core.`type`.DataTypes
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder
import com.dimafeng.testcontainers.CassandraContainer

import java.net.InetSocketAddress
import org.testcontainers.utility.DockerImageName
import zio.{ULayer, URLayer, ZEnvironment, ZIO, ZLayer}
import zio.ZIO.{attemptBlocking, fromCompletionStage => execAsync}

/**
 * A helper module for test-containers integration. Mostly this facilitates
 * spinning up Cassandra/Scylla containers, then obtaining the container's IP &
 * port, and finally creating the keyspace and the table for testing.
 */
object CassandraTestContainerSupport {

  case class CassandraV3(session: CqlSession)
  case class CassandraV4(session: CqlSession)
  case class ScyllaDb(session: CqlSession)

  private val cassandra        = "cassandra"
  private val testKeyspaceName = "CassandraKeyValueStoreSpec_Keyspace"
  private val testDataCenter   = "datacenter1"

  private val createKeyValueStoreTable =
    SchemaBuilder
      .createTable(testKeyspaceName, CassandraKeyValueStore.tableName)
      .withPartitionKey(
        CassandraKeyValueStore.namespaceColumnName,
        DataTypes.TEXT
      )
      .withClusteringColumn(
        CassandraKeyValueStore.keyColumnName,
        DataTypes.BLOB
      )
      .withClusteringColumn(
        CassandraKeyValueStore.timestampColumnName,
        DataTypes.BIGINT
      )
      .withColumn(
        CassandraKeyValueStore.valueColumnName,
        DataTypes.BLOB
      )
      .build

  private val createIndexedStoreTable =
    SchemaBuilder
      .createTable(testKeyspaceName, CassandraIndexedStore.tableName)
      .withPartitionKey(
        CassandraIndexedStore.topicColumnName,
        DataTypes.TEXT
      )
      .withClusteringColumn(
        CassandraIndexedStore.indexColumnName,
        DataTypes.BIGINT
      )
      .withColumn(
        CassandraIndexedStore.valueColumnName,
        DataTypes.BLOB
      )
      .build

  object DockerImageTag {
    val cassandraV3: String = s"$cassandra:3.11.11"
    val cassandraV4: String = s"$cassandra:4.0.1"
    val scyllaDb: String    = "scylladb/scylla:5.0.1"
  }

  val createKeyspaceScript: String =
    s"CREATE KEYSPACE $testKeyspaceName WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"

  lazy val cassandraV3: ZLayer[Any, Nothing, CassandraV3] =
    cassandraContainer(DockerImageTag.cassandraV3).fresh >>> cassandraSession.map(env =>
      ZEnvironment(CassandraV3(env.get))
    )

  lazy val cassandraV4: ZLayer[Any, Nothing, CassandraV4] =
    cassandraContainer(DockerImageTag.cassandraV4).fresh >>> cassandraSession.map(env =>
      ZEnvironment(CassandraV4(env.get))
    )

  lazy val scyllaDb: ZLayer[Any, Nothing, ScyllaDb] =
    cassandraContainer(DockerImageTag.scyllaDb).fresh >>> cassandraSession.map(env => ZEnvironment(ScyllaDb(env.get)))

  def cassandraSession: URLayer[CassandraContainer, CqlSession] =
    ZLayer.scoped {
      for {
        container <- ZIO.service[CassandraContainer]
        ipAddress <-
          ZIO.attempt {
            new InetSocketAddress(
              container.containerIpAddress,
              container.cassandraContainer.getFirstMappedPort
            )
          }
        _ <- ZIO.logInfo(s"Creating Cassandra session connecting to $ipAddress")
        _ <- createKeyspace(ipAddress)
        session <-
          ZIO.acquireRelease {
            execAsync(
              CqlSession.builder
                .addContactPoint(ipAddress)
                .withKeyspace(
                  CqlIdentifier.fromCql(testKeyspaceName)
                )
                .withLocalDatacenter(testDataCenter)
                .buildAsync()
            )
          } { session =>
            attemptBlocking {
              session.close()
            }.orDie
          }
      } yield session
    }.orDie

  def cassandraContainer(imageTag: String): ULayer[CassandraContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease {
        attemptBlocking {
          val container =
            CassandraContainer(
              DockerImageName
                .parse(imageTag)
                .asCompatibleSubstituteFor(cassandra)
            )
          container.start()
          container
        }.orDie
      } { container =>
        attemptBlocking(
          container.stop()
        ).orDie
      }
    }

  private def createKeyspace(ipAddress: InetSocketAddress) = ZIO.acquireRelease {
    for {
      session <-
        execAsync(
          CqlSession.builder
            .addContactPoint(ipAddress)
            .withLocalDatacenter(testDataCenter)
            .buildAsync()
        )
      _ <-
        execAsync(
          session.executeAsync(createKeyspaceScript)
        )
      _ <-
        execAsync(
          session.executeAsync(createKeyValueStoreTable)
        )
      _ <- execAsync(
             session.executeAsync(createIndexedStoreTable)
           )

    } yield session
  } { session =>
    attemptBlocking(
      session.close()
    ).orDie
  }
}
