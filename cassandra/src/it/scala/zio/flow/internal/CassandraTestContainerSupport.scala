package zio.flow.internal

import com.datastax.oss.driver.api.core.`type`.DataTypes
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder
import com.dimafeng.testcontainers.CassandraContainer

import java.net.InetSocketAddress
import org.testcontainers.utility.DockerImageName
import zio.{&, Has, URLayer, ZIO, ZManaged}
import zio.blocking.{Blocking, effectBlocking}
import zio.ZIO.{fromCompletionStage => execAsync}

object CassandraTestContainerSupport {

  type CassandraSessionLayer = URLayer[Blocking, Has[CqlSession]]

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
      .withPartitionKey(
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

  lazy val cassandraV3: CassandraSessionLayer =
    (cassandraContainer(DockerImageTag.cassandraV3) ++ Blocking.any) >>> cassandraSession

  lazy val cassandraV4: CassandraSessionLayer =
    (cassandraContainer(DockerImageTag.cassandraV4) ++ Blocking.any) >>> cassandraSession

  lazy val scyllaDb: CassandraSessionLayer =
    (cassandraContainer(DockerImageTag.scyllaDb) ++ Blocking.any) >>> cassandraSession

  lazy val cassandraSession: URLayer[Blocking & Has[CassandraContainer], Has[CqlSession]] = {
    for {
      container <- ZIO.service[CassandraContainer].toManaged_
      ipAddress <-
        ZIO.effect {
          new InetSocketAddress(
            container.containerIpAddress,
            container.cassandraContainer.getFirstMappedPort
          )
        }.toManaged_
      _ <- createKeyspace(ipAddress)
      session <-
        ZManaged.make {
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
          effectBlocking {
            session.close()
          }.orDie
        }
    } yield session
  }.orDie.toLayer

  def cassandraContainer(imageTag: String): URLayer[Blocking, Has[CassandraContainer]] =
    ZManaged.make {
      effectBlocking {
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
      effectBlocking(
        container.stop()
      ).orDie
    }.toLayer

  private def createKeyspace(ipAddress: InetSocketAddress) = ZManaged.make {
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
    effectBlocking(
      session.close()
    ).orDie
  }
}