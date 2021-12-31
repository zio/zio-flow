package zio.flow.internal

import zio.{Chunk, Has, URLayer, ZIO}
import zio.blocking.Blocking
import zio.test.Assertion.hasSameElements
import zio.test.TestAspect.{nondeterministic, sequential}
import zio.test.{DefaultRunnableSpec, Gen, ZSpec, assert, assertTrue, checkNM}

import java.time.Instant

object CassandraKeyValueStoreSpec extends DefaultRunnableSpec {

  private val cqlNameGen =
    Gen.alphaNumericStringBounded(
      min = 1,
      max = 48
    )

  private val nonEmptyByteChunkGen =
    Gen.chunkOf1(Gen.anyByte)

  private val byteChunkGen =
    Gen.chunkOf(Gen.anyByte)

  private val cassandraV3 =
    CassandraTestContainerSupport.cassandraV3 >>> CassandraKeyValueStore.live

  private val cassandraV4 =
    CassandraTestContainerSupport.cassandraV4 >>> CassandraKeyValueStore.live

  private val scyllaDb =
    CassandraTestContainerSupport.scyllaDb >>> CassandraKeyValueStore.live

  override def spec: ZSpec[Environment, Failure] =
    suite("CassandraKeyValueStoreSpec")(
      testUsing(cassandraV3, "Cassandra V3"),
      testUsing(cassandraV4, "Cassandra V4"),
      testUsing(scyllaDb, "ScyllaDB")
    )

  private def testUsing(keyValueStore: URLayer[Blocking, Has[KeyValueStore]], label: String) =
    suite(label)(
      testM("should be able to `put` a key-value pair and then `get` it back.") {
        checkNM(10)(
          cqlNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen
        ) { (tableName, key, value) =>
          for {
            putSucceeded <- KeyValueStore.put(tableName, key, value)
            retrieved    <- KeyValueStore.get(tableName, key)
          } yield assertTrue(
            retrieved.get == value,
            putSucceeded
          )
        }
      },
      testM("should return empty result for a `get` call when the table does not exist.") {
        checkNM(10)(
          nonEmptyByteChunkGen
        ) { key =>
          val nonExistingTableName = newTimeBasedName()

          KeyValueStore
            .get(nonExistingTableName, key)
            .map { retrieved =>
              assertTrue(
                retrieved.isEmpty
              )
            }
        }
      },
      testM("should return empty result for a `get` call when the key does not exist.") {
        checkNM(10)(
          cqlNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen
        ) { (tableName, key, value) =>
          val nonExistingKey =
            Chunk.fromIterable(newTimeBasedName().getBytes)

          for {
            putSucceeded <- KeyValueStore.put(tableName, key, value)
            retrieved    <- KeyValueStore.get(tableName, nonExistingKey)
          } yield assertTrue(
            retrieved.isEmpty,
            putSucceeded
          )
        }
      },
      testM("should return empty result for a `scanAll` call when the table does not exist.") {
        val nonExistingTableName = newTimeBasedName()

        KeyValueStore
          .scanAll(nonExistingTableName)
          .runCollect
          .map { retrieved =>
            assertTrue(
              retrieved.isEmpty
            )
          }
      },
      testM("should return all key-value pairs for a `scanAll` call.") {
        val uniqueTableName = newTimeBasedName()
        val keyValuePairs =
          Chunk
            .fromIterable(1 to 5001)
            .map { n =>
              Chunk.fromArray(s"abc_$n".getBytes) -> Chunk.fromArray(s"xyz_$n".getBytes)
            }
        val expectedLength = keyValuePairs.length

        for {
          putSuccesses <-
            ZIO
              .foreach(keyValuePairs) { case (key, value) =>
                KeyValueStore.put(uniqueTableName, key, value)
              }
          retrieved <-
            KeyValueStore
              .scanAll(uniqueTableName)
              .runCollect
        } yield {
          assertTrue(
            putSuccesses.length == expectedLength,
            putSuccesses.toSet == Set(true),
            retrieved.length == expectedLength
          ) &&
          assert(retrieved)(
            hasSameElements(keyValuePairs)
          )
        }
      }
    ).provideCustomLayerShared(keyValueStore) @@ nondeterministic @@ sequential

  private def newTimeBasedName() =
    s"${Instant.now}"
      .replaceAll(":", "_")
      .replaceAll(".", "_")
}
