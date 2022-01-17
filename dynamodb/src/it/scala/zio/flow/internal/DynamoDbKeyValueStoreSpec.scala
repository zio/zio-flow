package zio.flow.internal

import DynamoDbKeyValueStore.tableName
import DynamoDbSupport.{createDynamoDbTable, dynamoDbLayer}
import zio.{Chunk, ZIO}
import zio.test.Assertion.hasSameElements
import zio.test.TestAspect.{nondeterministic, sequential}
import zio.test.{DefaultRunnableSpec, Gen, ZSpec, assert, assertTrue, checkN}

object DynamoDbKeyValueStoreSpec extends DefaultRunnableSpec {

  private val dynamoDbKeyValueStore =
    dynamoDbLayer >+> DynamoDbKeyValueStore.live

  private val dynamoDbNameGen =
    Gen.alphaNumericStringBounded(
      min = 3,
      max = 255
    )

  private val nonEmptyByteChunkGen =
    Gen.chunkOf1(Gen.byte)

  private val byteChunkGen =
    Gen.chunkOf(Gen.byte)

  override def spec: ZSpec[Environment, Failure] =
    suite("DynamoDbKeyValueStoreSpec")(
      test("should be able to `put` (upsert) a key-value pair and then `get` it back.") {
        checkN(10)(
          dynamoDbNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen,
          byteChunkGen
        ) { (namespace, key, value1, value2) =>
          createDynamoDbTable(tableName).use { _ =>
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
        }
      },
      test("should return empty result for a `get` call when the namespace does not exist.") {
        checkN(10)(
          nonEmptyByteChunkGen
        ) { key =>
          val nonExistentNamespace = newTimeBasedName()

          createDynamoDbTable(tableName).use { _ =>
            KeyValueStore
              .get(nonExistentNamespace, key)
              .map { retrieved =>
                assertTrue(
                  retrieved.isEmpty
                )
              }
          }
        }
      },
      test("should return empty result for a `get` call when the key does not exist.") {
        checkN(10)(
          dynamoDbNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen
        ) { (namespace, key, value) =>
          val nonExistingKey =
            Chunk.fromIterable(newTimeBasedName().getBytes)

          createDynamoDbTable(tableName).use { _ =>
            for {
              putSucceeded <- KeyValueStore.put(namespace, key, value)
              retrieved    <- KeyValueStore.get(namespace, nonExistingKey)
            } yield assertTrue(
              retrieved.isEmpty,
              putSucceeded
            )
          }
        }
      },
      test("should return empty result for a `scanAll` call when the namespace does not exist.") {
        val nonExistentNamespace = newTimeBasedName()

        createDynamoDbTable(tableName).use { _ =>
          KeyValueStore
            .scanAll(nonExistentNamespace)
            .runCollect
            .map { retrieved =>
              assertTrue(retrieved.isEmpty)
            }
        }
      },
      test("should return all key-value pairs for a `scanAll` call.") {
        val uniqueTableName = newTimeBasedName()
        val keyValuePairs =
          Chunk
            .fromIterable(1 to 1001)
            .map { n =>
              Chunk.fromArray(s"abc_$n".getBytes) -> Chunk.fromArray(s"xyz_$n".getBytes)
            }
        val expectedLength = keyValuePairs.length

        createDynamoDbTable(tableName).use { _ =>
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
      }
    ).provideCustomLayerShared(dynamoDbKeyValueStore) @@ nondeterministic @@ sequential

  private def newTimeBasedName() =
    s"${java.time.Instant.now}"
      .replaceAll(":", "_")
      .replaceAll(".", "_")
}