package zio.flow.cassandra

import com.datastax.oss.driver.api.core.`type`.DataTypes
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder
import com.dimafeng.testcontainers.CassandraContainer

import java.net.InetSocketAddress
import org.testcontainers.utility.DockerImageName
import zio.{ULayer, URLayer, ZIO, ZLayer}
import zio.ZIO.{attemptBlocking, fromCompletionStage => execAsync}

/**
 * A helper module for test-containers integration. Mostly this facilitates
 * spinning up Cassandra/Scylla containers, then obtaining the container's IP &
 * port, and finally creating the keyspace and the table for testing.
 */
object CassandraTestContainerSupport {

  type SessionLayer = ULayer[CqlSession]

  private val cassandra        = "cassandra"
  private val testKeyspaceName = "CassandraKeyValueStoreSpec_Keyspace"
  private val testDataCenter   = "datacenter1"

  private val createTable =
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
      .withColumn(
        CassandraKeyValueStore.valueColumnName,
        DataTypes.BLOB
      )
      .build

  object DockerImageTag {
    val cassandraV3: String = s"$cassandra:3.11.11"
    val cassandraV4: String = s"$cassandra:4.0.1"
    val scyllaDb: String =
      // This nightly build supports Apple M1; Will point to a regular version when v4.6 is released.
      "scylladb/scylla-nightly:4.6.rc1-0.20211227.283788828"
  }

  val createKeyspaceScript: String =
    s"CREATE KEYSPACE $testKeyspaceName WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};"

  lazy val cassandraV3: SessionLayer =
    cassandraContainer(DockerImageTag.cassandraV3) >>> cassandraSession

  lazy val cassandraV4: SessionLayer =
    cassandraContainer(DockerImageTag.cassandraV4) >>> cassandraSession

  lazy val scyllaDb: SessionLayer =
    cassandraContainer(DockerImageTag.scyllaDb) >>> cassandraSession

  lazy val cassandraSession: URLayer[CassandraContainer, CqlSession] =
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
          session.executeAsync(createTable)
        )
    } yield session
  } { session =>
    attemptBlocking(
      session.close()
    ).orDie
  }
}
