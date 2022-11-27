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

import zio.Scope
import zio.flow.runtime.internal.Generators.genScopedRemoteVariableName
import zio.flow.{FlowId, RemoteVariableName, ZIOFlowBaseSpec}
import zio.test._

object ScopedRemoteVariableNameSpec extends ZIOFlowBaseSpec with zio.flow.serialization.Generators {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("ScopedRemoteVariableName")(
      suite("conversion to string is invertible")(
        test("property") {
          check(genScopedRemoteVariableName) { name =>
            assertInvertible(name)
          }
        },
        test("top level") {
          val name = ScopedRemoteVariableName(RemoteVariableName("x"), RemoteVariableScope.TopLevel(FlowId("flow1")))
          assertInvertible(name)
        },
        test("fiber") {
          val name = ScopedRemoteVariableName(
            RemoteVariableName("x"),
            RemoteVariableScope.Fiber(FlowId("flow1_flow2"), RemoteVariableScope.TopLevel(FlowId("flow1")))
          )
          assertInvertible(name)
        }
      )
    ) @@ TestAspect.size(500) @@ TestAspect.samples(1000)

  private def assertInvertible(name: ScopedRemoteVariableName) =
    assertTrue(
      ScopedRemoteVariableName.fromString(name.asString).get == name
    ) ?? s"encoded form: ${name.asString}"
}
