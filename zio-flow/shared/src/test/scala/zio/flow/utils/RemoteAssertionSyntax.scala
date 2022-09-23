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

package zio.flow.utils

import zio.ZIO
import zio.flow._
import zio.schema.Schema
import zio.test.Assertion.{equalTo, fails, succeeds}
import zio.test.{TestResult, assertZIO}

object RemoteAssertionSyntax {

  implicit final class RemoteAssertionOps[A: Schema](private val self: Remote[A]) {
    def <->[A1 <: A](that: A1): ZIO[RemoteContext with LocalContext, Nothing, TestResult] =
      assertZIO(self.eval[A].exit)(succeeds(equalTo(that)))

    def failsWithRemoteError(message: String): ZIO[RemoteContext with LocalContext, Nothing, TestResult] =
      assertZIO(self.eval[A].exit)(fails(equalTo(RemoteEvaluationError.RemoteFail(message))))
  }
}
