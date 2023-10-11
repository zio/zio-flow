/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

package zio.flow.remote

import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow._
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test.{Spec, TestEnvironment, TestResult}
import zio.{ZIO, ZLayer}

object RemoteTupleSpec extends RemoteSpecBase {

  override def spec: Spec[TestEnvironment, Nothing] =
    suite("RemoteTupleSpec")(
      test("Tuple2") {
        val tuple2 = Remote((1, "A"))
        ZIO
          .collectAll(
            List(
              tuple2._1 <-> 1,
              tuple2._2 <-> "A"
            )
          )
          .map(rs => TestResult.allSuccesses(rs.head, rs.tail: _*))
      },
      test("Tuple3") {
        val tuple3 = Remote((1, "A", true))
        ZIO
          .collectAll(
            List(
              tuple3._1 <-> 1,
              tuple3._2 <-> "A",
              tuple3._3 <-> true
            )
          )
          .map(rs => TestResult.allSuccesses(rs.head, rs.tail: _*))
      },
      test("Tuple4") {
        val tuple4 = Remote((1, "A", true, 10.5))
        ZIO
          .collectAll(
            List(
              tuple4._1 <-> 1,
              tuple4._2 <-> "A",
              tuple4._3 <-> true,
              tuple4._4 <-> 10.5
            )
          )
          .map(rs => TestResult.allSuccesses(rs.head, rs.tail: _*))
      },
      test("Tuple5") {
        val tuple5 = Remote((1, "A", true, 10.5, "X"))
        ZIO
          .collectAll(
            List(
              tuple5._1 <-> 1,
              tuple5._2 <-> "A",
              tuple5._3 <-> true,
              tuple5._4 <-> 10.5,
              tuple5._5 <-> "X"
            )
          )
          .map(rs => TestResult.allSuccesses(rs.head, rs.tail: _*))
      }
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)

}
