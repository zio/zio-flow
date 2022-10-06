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

package zio.flow.test

import zio.{Chunk, Scope, ZIO}
import zio.flow.internal._
import zio.test._
import zio.test.Assertion.{hasSameElements, isNone}
import zio.test.TestAspect._

final case class KeyValueStoreTests[R](name: String, initializeDb: ZIO[R with Scope, Throwable, Any]) {
  def tests: Spec[TestEnvironment with R with KeyValueStore, Throwable] =
    suite(name)(
      test("should be able to `put` (upsert) a key-value pair and then `get` it back.") {
        checkN(10)(
          Generators.namespace,
          Generators.nonEmptyByteChunkGen,
          Generators.byteChunkGen,
          Generators.byteChunkGen
        ) { (namespace, key, value1, value2) =>
          ZIO.scoped[R with KeyValueStore] {
            initializeDb *> {
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
        }
      },
      test("should be able to delete a key-value pair") {
        checkN(10)(
          Generators.namespace,
          Generators.nonEmptyByteChunkGen,
          Generators.byteChunkGen
        ) { (namespace, key, value1) =>
          ZIO.scoped[R with KeyValueStore] {
            initializeDb *> {
              for {
                _      <- KeyValueStore.put(namespace, key, value1, Timestamp(1L))
                _      <- KeyValueStore.delete(namespace, key)
                latest <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(1)))
              } yield assert(latest)(isNone)
            }
          }
        }
      },
      test("should return empty result for a `get` call when the namespace does not exist.") {
        checkN(10)(
          Generators.nonEmptyByteChunkGen
        ) { key =>
          val nonExistentNamespace = Generators.newTimeBasedName()

          ZIO.scoped[R with KeyValueStore] {
            initializeDb *>
              KeyValueStore
                .getLatest(nonExistentNamespace, key, None)
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
          Generators.namespace,
          Generators.nonEmptyByteChunkGen,
          Generators.byteChunkGen
        ) { (namespace, key, value) =>
          val nonExistingKey =
            Chunk.fromIterable(Generators.newTimeBasedName().getBytes)

          ZIO.scoped[R with KeyValueStore] {
            initializeDb *> {
              for {
                putSucceeded <- KeyValueStore.put(namespace, key, value, Timestamp(1L))
                retrieved    <- KeyValueStore.getLatest(namespace, nonExistingKey, Some(Timestamp(2L)))
              } yield assertTrue(
                retrieved.isEmpty,
                putSucceeded
              )
            }
          }
        }
      },
      test("should return empty result for a `scanAll` call when the namespace does not exist.") {
        val nonExistentNamespace = Generators.newTimeBasedName()
        ZIO.scoped[R with KeyValueStore] {
          initializeDb *>
            KeyValueStore
              .scanAll(nonExistentNamespace)
              .runCollect
              .map { retrieved =>
                assertTrue(retrieved.isEmpty)
              }

        }
      },
      test("should return all key-value pairs for a `scanAll` call.") {
        val uniqueTableName = Generators.newTimeBasedName()
        val keyValuePairs =
          Chunk
            .fromIterable(1 to 1001)
            .map { n =>
              Chunk.fromArray(s"abc_$n".getBytes) -> Chunk.fromArray(s"xyz_$n".getBytes)
            }
        val expectedLength = keyValuePairs.length

        ZIO.scoped[R with KeyValueStore] {
          initializeDb *> {
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
        }
      },
      test("should return all keys for a `scanAllKeys` call.") {
        val uniqueTableName = Generators.newTimeBasedName()
        val keyValuePairs =
          Chunk
            .fromIterable(1 to 1001)
            .map { n =>
              Chunk.fromArray(s"abc_$n".getBytes) -> Chunk.fromArray(s"xyz_$n".getBytes)
            }
        val expectedLength = keyValuePairs.length

        ZIO.scoped[R with KeyValueStore] {
          initializeDb *> {
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
      }
    ) @@ nondeterministic @@ sequential
}
