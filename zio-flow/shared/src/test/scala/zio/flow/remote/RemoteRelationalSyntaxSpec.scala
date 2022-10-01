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

package zio.flow.remote

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{LocalContext, Remote, RemoteContext}
import zio.test.{Gen, Sized, TestResult, check}
import zio.{ZIO, ZLayer}

object RemoteRelationalSyntaxSpec extends RemoteSpecBase {

  val smallIntGen: Gen[Sized, Int] =
    Gen.small(Gen.const(_))

  override def spec =
    suite("RemoteRelationalSpec")(
      test("Int") {
        check(smallIntGen, smallIntGen) { case (x, y) =>
          ZIO
            .collectAll(
              List(
                Remote(x) < Remote(y) <-> (x < y),
                Remote(x) <= Remote(y) <-> (x <= y),
                (Remote(x) !== Remote(y)) <-> (x != y),
                Remote(x) > Remote(y) <-> (x > y),
                Remote(x) >= Remote(y) <-> (x >= y),
                (Remote(x) === Remote(y)) <-> (x == y)
              )
            )
            .map(TestResult.all(_: _*))
        }
      },
      test("String") {
        check(Gen.string, Gen.string) { case (x, y) =>
          ZIO
            .collectAll(
              List(
                Remote(x) < Remote(y) <-> (x < y),
                Remote(x) <= Remote(y) <-> (x <= y),
                (Remote(x) !== Remote(y)) <-> (x != y),
                Remote(x) > Remote(y) <-> (x > y),
                Remote(x) >= Remote(y) <-> (x >= y),
                (Remote(x) === Remote(y)) <-> (x == y)
              )
            )
            .map(TestResult.all(_: _*))
        }
      }
    ).provideCustom(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
}
