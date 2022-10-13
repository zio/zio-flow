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

package zio.flow.runtime.test

import zio.{Chunk, Scope, ZIO}
import zio.flow.runtime.IndexedStore.Index
import zio.flow.runtime.IndexedStore
import zio.test.Assertion.{containsString, equalTo}
import zio.test.TestAspect.{nondeterministic, sequential}
import zio.test.{Spec, TestEnvironment, assertTrue, assertZIO, test, suite}

final case class IndexedStoreTests[R](name: String, initializeDb: ZIO[R with Scope, Throwable, Any]) {
  def tests: Spec[TestEnvironment with R with IndexedStore, Throwable] =
    suite(name)(
      test("Test single put") {
        ZIO.scoped[R with IndexedStore] {
          for {
            _            <- initializeDb
            indexedStore <- ZIO.service[IndexedStore]
            insertPos    <- indexedStore.put("SomeTopic1", Chunk.fromArray("Value1".getBytes()))
          } yield assertTrue(insertPos == Index(1L))
        }
      },
      test("Test sequential put") {
        ZIO.scoped[R with IndexedStore] {
          for {
            _            <- initializeDb
            indexedStore <- ZIO.service[IndexedStore]
            posList <- ZIO.foreach((0 until 10).toList)(i =>
                         indexedStore.put("SomeTopic2", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                       )
            _ <- ZIO.debug(posList.mkString(","))
          } yield assertTrue(posList.mkString(",") == "1,2,3,4,5,6,7,8,9,10")
        }
      },
      test("Test scan on empty topic") {
        ZIO.scoped[R with IndexedStore] {
          for {
            _            <- initializeDb
            indexedStore <- ZIO.service[IndexedStore]
            scannedChunk <- indexedStore.scan("SomeTopic3", Index(1L), Index(10L)).runCollect
            resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
          } yield assertTrue(resultChunk.toList.mkString("") == "")
        }
      },
      test("Test sequential put and scan") {
        ZIO.scoped[R with IndexedStore] {
          for {
            _            <- initializeDb
            indexedStore <- ZIO.service[IndexedStore]
            _ <- ZIO.foreachDiscard((0 until 10).toList) { i =>
                   indexedStore.put("SomeTopic4", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                 }
            scannedChunk <- indexedStore.scan("SomeTopic4", Index(1L), Index(10L)).runCollect
            resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
          } yield assertTrue(
            resultChunk.toList.mkString(",") == "Value0,Value1,Value2,Value3,Value4,Value5,Value6,Value7,Value8,Value9"
          )
        }
      },
      test("Test concurrent put and scan") {
        val resChunk =
          ZIO.scoped[R with IndexedStore] {
            for {
              _            <- initializeDb
              indexedStore <- ZIO.service[IndexedStore]
              _ <- ZIO.foreachParDiscard((0 until 10).toList)(i =>
                     indexedStore.put("SomeTopic5", Chunk.fromArray(s"Value${i.toString}".getBytes()))
                   )
              scannedChunk <- indexedStore.scan("SomeTopic5", Index(1L), Index(10L)).runCollect
              resultChunk  <- ZIO.succeed(scannedChunk.map(bytes => new String(bytes.toArray)))
            } yield resultChunk
          }
        assertZIO(resChunk.map(_.size))(equalTo(10)) *>
          assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value9")) *>
          assertZIO(resChunk.map(_.toList.mkString(",")))(containsString("Value0"))
      }
    ) @@ nondeterministic @@ sequential
}
