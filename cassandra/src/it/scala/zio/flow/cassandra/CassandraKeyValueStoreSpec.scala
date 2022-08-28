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

import CassandraTestContainerSupport._
import zio.{Chunk, ZIO}
import zio.flow.internal._
import zio.test.Assertion.hasSameElements
import zio.test.TestAspect.{nondeterministic, sequential}
import zio.test.{Gen, Spec, assert, assertTrue, checkN, ZIOSpecDefault}
import zio.test.Assertion.isNone

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
            putSucceeded2 <- KeyValueStore.put(namespace, key, value2, Timestamp(2L))
            retrieved1    <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(1L)))
            retrieved2    <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(2L)))
          } yield assertTrue(
            putSucceeded1,
            retrieved1.get == value1,
            putSucceeded2,
            retrieved2.get == value2
          )
        }
      },
      test("should be able to delete a key-value pair") {
        checkN(10)(
          cqlNameGen,
          nonEmptyByteChunkGen,
          byteChunkGen
        ) { (namespace, key, value1) =>
          for {
            _      <- KeyValueStore.put(namespace, key, value1, Timestamp(1L))
            _      <- KeyValueStore.delete(namespace, key)
            latest <- KeyValueStore.getLatest(namespace, key, Some(Timestamp(1)))
          } yield assert(latest)(isNone)
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
      },
      test("should return all keys pairs for a `scanAllKeys` call.") {
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
              .scanAllKeys(uniqueNamespace)
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
    ).provideCustomLayerShared(database >>> CassandraKeyValueStore.layer) @@ nondeterministic @@ sequential

  private def newTimeBasedName() =
    s"${java.time.Instant.now}"
      .replaceAll(":", "_")
      .replaceAll(".", "_")
}
