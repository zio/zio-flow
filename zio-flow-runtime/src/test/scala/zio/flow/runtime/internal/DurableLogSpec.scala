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

package zio.flow.runtime.internal

import zio.flow.runtime.IndexedStore.Index
import zio.flow.runtime.{DurableLog, IndexedStore}
import zio.test.Assertion.isEmpty
import zio.test.{Gen, Sized, ZIOSpecDefault, assert, assertTrue, check}
import zio.{Chunk, ZIO, ZLayer}

object DurableLogSpec extends ZIOSpecDefault {

  val durableLog: ZLayer[Any, Nothing, DurableLog with IndexedStore] =
    IndexedStore.inMemory >+> DurableLog.layer

  val values: Gen[Sized, Chunk[Chunk[Byte]]] =
    Gen.chunkOf(Gen.chunkOf(Gen.byte)).noShrink

  def spec =
    suite("DurableLogSpec")(
      test("sequential read write") {
        check(values) { in =>
          (for {
            _   <- ZIO.foreachDiscard(in)(DurableLog.append("partition", _))
            out <- DurableLog.subscribe("partition", Index(0L)).take(in.length.toLong).runCollect
          } yield assertTrue(out == in)).provideLayer(durableLog)
        }
      },
      test("concurrent read write") {
        check(values) { in =>
          (for {
            writer <- ZIO.foreach(in)(DurableLog.append("partition", _)).fork
            reader <- DurableLog.subscribe("partition", Index(0L)).take(in.length.toLong).runCollect.fork
            _      <- writer.join
            out    <- reader.join
          } yield assertTrue(out == in)).provideLayer(durableLog)
        }
      },
      test("read available items") {
        (for {
          before <- DurableLog.getAllAvailable("partition", Index(0L)).runCollect
          _      <- DurableLog.append("partition", Chunk(0))
          _      <- DurableLog.append("partition", Chunk(1))
          _      <- DurableLog.append("partition", Chunk(2))
          after  <- DurableLog.getAllAvailable("partition", Index(1L)).runCollect
        } yield assert(before)(isEmpty) && assertTrue(after == Chunk(Chunk(1.toByte), Chunk(2.toByte))))
          .provideLayer(durableLog)
      }
    )
}
