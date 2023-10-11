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

import zio.ZIO
import zio.flow.{LocalContext, RemoteContext, ZIOFlowBaseSpec}
import zio.test.{Spec, TestResult}

trait RemoteSpecBase extends ZIOFlowBaseSpec {
  protected def remoteTest(name: String)(
    cases: ZIO[RemoteContext with LocalContext, Nothing, TestResult]*
  ): Spec[RemoteContext with LocalContext, Nothing] =
    test(name)(
      ZIO
        .collectAll(cases.toList)
        .map(rs => TestResult.allSuccesses(rs.head, rs.tail: _*))
    )

}
