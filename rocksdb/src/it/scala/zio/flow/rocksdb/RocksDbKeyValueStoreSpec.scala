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

package zio.flow.rocksdb

import zio.flow.internal.{KeyValueStore, Timestamp}
import zio.nio.file.Files
import zio.test.Assertion.hasSameElements
import zio.test.TestAspect.{nondeterministic, sequential}
import zio.test.ZIOSpecDefault
import zio.test.{Gen, assert, assertTrue, checkN}
import zio.{Chunk, ZIO, ZLayer}

object RocksDbKeyValueStoreSpec extends ZIOSpecDefault {

  private val config = ZLayer.scoped {
    Files.createTempDirectoryScoped(Some("zio-rocksdb"), Seq()).map { path =>
      RocksDbConfig(path.toFile.toPath)
    }
  }

  private val kVStoreLayer = config >>> RocksDbKeyValueStore.layer

  private def newTimeBasedName() =
    s"${java.time.Instant.now}"
      .replaceAll(":", "_")
      .replaceAll(".", "_")

  private val rocksNamespaceGen =
    Gen.alphaNumericStringBounded(
      min = 3,
      max = 255
    )

  private val nonEmptyByteChunkGen =
    Gen.chunkOf1(Gen.byte)

  private val byteChunkGen =
    Gen.chunkOf(Gen.byte)

  def spec =
    suite("RocksDbKeyValueStoreSpec")(
      test("should be able to `put` (upsert) a key-value pair and then `get` it back.") {
        checkN(10)(
          rocksNamespaceGen,
          nonEmptyByteChunkGen,
          byteChunkGen,
          byteChunkGen
        ) { (namespace, key, value1, value2) =>
          ZIO.scoped {
            // The rocksdb kv store takes care of creating non-existing namespaces
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
        }
      },
      test("should return empty result for a `get` call when the namespace does not exist.") {
        checkN(10)(
          nonEmptyByteChunkGen
        ) { key =>
          val nonExistentNamespace = newTimeBasedName()

          KeyValueStore
            .getLatest(nonExistentNamespace, key, None)
            .map { retrieved =>
              assertTrue(
                retrieved.isEmpty
              )
            }
        }
      },
      test("should return empty result for a `get` call when the key does not exist.") {
        checkN(10)(
          rocksNamespaceGen,
          nonEmptyByteChunkGen,
          byteChunkGen
        ) { (namespace, key, value) =>
          val nonExistingKey =
            Chunk.fromIterable(newTimeBasedName().getBytes)

          ZIO.scoped {

            for {
              putSucceeded <- KeyValueStore.put(namespace, key, value, Timestamp(1L))
              retrieved    <- KeyValueStore.getLatest(namespace, nonExistingKey, Some(Timestamp(2L)))
            } yield assertTrue(
              retrieved.isEmpty,
              putSucceeded
            )

          }
        }
      },
      test("should return empty result for a `scanAll` call when the namespace does not exist.") {
        val nonExistentNamespace = newTimeBasedName()
        ZIO.scoped {
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

        ZIO.scoped {

          for {
            putSuccesses <-
              ZIO
                .foreach(keyValuePairs) { case (key, value) =>
                  KeyValueStore.put(uniqueTableName, key, value, Timestamp(1L))
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
      },
      test("should return all keys for a `scanAllKeys` call.") {
        val uniqueTableName = newTimeBasedName()
        val keyValuePairs =
          Chunk
            .fromIterable(1 to 1001)
            .map { n =>
              Chunk.fromArray(s"abc_$n".getBytes) -> Chunk.fromArray(s"xyz_$n".getBytes)
            }
        val expectedLength = keyValuePairs.length

        ZIO.scoped {

          for {
            putSuccesses <-
              ZIO
                .foreach(keyValuePairs) { case (key, value) =>
                  KeyValueStore.put(uniqueTableName, key, value, Timestamp(1L))
                }
            retrieved <-
              KeyValueStore
                .scanAllKeys(uniqueTableName)
                .runCollect
          } yield {
            assertTrue(
              putSuccesses.length == expectedLength,
              putSuccesses.toSet == Set(true),
              retrieved.length == expectedLength
            ) &&
            assert(retrieved)(
              hasSameElements(keyValuePairs.map(_._1))
            )
          }
        }
      }
    ).provideCustom(kVStoreLayer) @@ nondeterministic @@ sequential

}
