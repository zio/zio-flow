package zio.flow.cassandra

import CassandraTestContainerSupport.{SessionLayer, cassandraV3, cassandraV4, scyllaDb}
import zio.flow.internal.KeyValueStore
import zio.{Chunk, ZIO}
import zio.test.Assertion.hasSameElements
import zio.test.TestAspect.{nondeterministic, sequential}
import zio.test.{Gen, Spec, assert, assertTrue, checkN, ZIOSpecDefault}
import zio.flow.internal.Timestamp

object CassandraKeyValueStoreSpec extends ZIOSpecDefault {

  private val cqlNameGen =
    Gen.alphaNumericStringBounded(
      min = 1,
      max = 48
    )

  private val nonEmptyByteChunkGen =
    Gen.chunkOf1(Gen.byte)

  private val byteChunkGen =
    Gen.chunkOf(Gen.byte)

  override def spec: Spec[Environment, Any] =
    suite("CassandraKeyValueStoreSpec")(
      testUsing(cassandraV3, "Cassandra V3"),
      testUsing(cassandraV4, "Cassandra V4"),
      testUsing(scyllaDb, "ScyllaDB")
    )

  private def testUsing(database: SessionLayer, label: String) =
    suite(label)(
      test("should be able to `put` (upsert) a key-value pair and then `get` it back.") {
        checkN(10)(
          cqlNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen,
          byteChunkGen
        ) { (namespace, key, value1, value2) =>
          for {
            putSucceeded1 <- KeyValueStore.put(namespace, key, value1, Timestamp(1L))
            retrieved1    <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(1L)))
            putSucceeded2 <- KeyValueStore.put(namespace, key, value2, Timestamp(2L))
            retrieved2    <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(2L)))
          } yield assertTrue(
            putSucceeded1,
            retrieved1.get == value1,
            putSucceeded2,
            retrieved2.get == value2
          )
        }
      },
      test("should return empty result for a `get` call when the namespace does not exist.") {
        checkN(10)(
          nonEmptyByteChunkGen
        ) { key =>
          val nonExistentNamespace = newTimeBasedName()

          KeyValueStore
            .getLatest(nonExistentNamespace, key, before = None)
            .map { retrieved =>
              assertTrue(
                retrieved.isEmpty
              )
            }
        }
      },
      test("should return empty result for a `get` call when the key does not exist.") {
        checkN(10)(
          cqlNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen
        ) { (namespace, key, value) =>
          val nonExistingKey =
            Chunk.fromIterable(newTimeBasedName().getBytes)

          for {
            putSucceeded <- KeyValueStore.put(namespace, key, value, Timestamp(1L))
            retrieved    <- KeyValueStore.getLatest(namespace, nonExistingKey, Some(Timestamp(1L)))
          } yield assertTrue(
            retrieved.isEmpty,
            putSucceeded
          )
        }
      },
      test("should return empty result for a `scanAll` call when the namespace does not exist.") {
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
      test("should return all key-value pairs for a `scanAll` call.") {
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
              .foreachPar(keyValuePairs) { case (key, value) =>
                KeyValueStore.put(uniqueNamespace, key, value, Timestamp(1L))
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
    ).provideCustomLayerShared(database >>> CassandraKeyValueStore.layer) @@ nondeterministic @@ sequential

  private def newTimeBasedName() =
    s"${java.time.Instant.now}"
      .replaceAll(":", "_")
      .replaceAll(".", "_")
}
