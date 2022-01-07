package zio.flow.internal

import zio.{Chunk, Has, URLayer, ZIO}
import zio.blocking.Blocking
import zio.test.Assertion.hasSameElements
import zio.test.TestAspect.{nondeterministic, sequential}
import zio.test.{DefaultRunnableSpec, Gen, ZSpec, assert, assertTrue, checkNM}

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
      testM("should be able to `put` (upsert) a key-value pair and then `get` it back.") {
        checkNM(10)(
          cqlNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen,
          byteChunkGen
        ) { (namespace, key, value1, value2) =>
          for {
            putSucceeded1 <- KeyValueStore.put(namespace, key, value1)
            retrieved1    <- KeyValueStore.get(namespace, key)
            putSucceeded2 <- KeyValueStore.put(namespace, key, value2)
            retrieved2    <- KeyValueStore.get(namespace, key)
          } yield assertTrue(
            putSucceeded1,
            retrieved1.get == value1,
            putSucceeded2,
            retrieved2.get == value2
          )
        }
      },
      testM("should return empty result for a `get` call when the namespace does not exist.") {
        checkNM(10)(
          nonEmptyByteChunkGen
        ) { key =>
          val nonExistentNamespace = newTimeBasedName()

          KeyValueStore
            .get(nonExistentNamespace, key)
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
        ) { (namespace, key, value) =>
          val nonExistingKey =
            Chunk.fromIterable(newTimeBasedName().getBytes)

          for {
            putSucceeded <- KeyValueStore.put(namespace, key, value)
            retrieved    <- KeyValueStore.get(namespace, nonExistingKey)
          } yield assertTrue(
            retrieved.isEmpty,
            putSucceeded
          )
        }
      },
      testM("should return empty result for a `scanAll` call when the namespace does not exist.") {
        val nonExistentNamespace = newTimeBasedName()

        KeyValueStore
          .scanAll(nonExistentNamespace)
          .runCollect
          .map { retrieved =>
            assertTrue(
              retrieved.isEmpty
            )
          }
      },
      testM("should return all key-value pairs for a `scanAll` call.") {
        val uniqueNamespace = newTimeBasedName()
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
                KeyValueStore.put(uniqueNamespace, key, value)
              }
          retrieved <-
            KeyValueStore
              .scanAll(uniqueNamespace)
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
    s"${java.time.Instant.now}"
      .replaceAll(":", "_")
      .replaceAll(".", "_")
}
