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

import zio.{ZIO, ZLayer}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{LocalContext, Remote, RemoteContext}
import zio.test.TestResult

object RemoteBooleanSpec extends RemoteSpecBase {
  override def spec =
    suite("RemoteBooleanSpec")(
      test("And") {
        ZIO
          .collectAll(
            List(
              (Remote(true) && Remote(false)) <-> false,
              (Remote(true) && Remote(true)) <-> true,
              (Remote(false) && Remote(false)) <-> false
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("Or") {
        ZIO
          .collectAll(
            List(
              (Remote(true) || Remote(false)) <-> true,
              (Remote(true) || Remote(true)) <-> true,
              (Remote(false) || Remote(false)) <-> false
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("Not") {
        ZIO
          .collectAll(
            List(
              !Remote(true) <-> false,
              !Remote(false) <-> true
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("IfThenElse") {
        ZIO
          .collectAll(
            List(
              Remote(false).ifThenElse(Remote(1), Remote(12)) <-> 12,
              Remote(true).ifThenElse(Remote(1), Remote(12)) <-> 1
            )
          )
          .map(TestResult.all(_: _*))
      }
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

}
