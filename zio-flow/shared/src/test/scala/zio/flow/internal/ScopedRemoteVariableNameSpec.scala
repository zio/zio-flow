package zio.flow.internal

import zio.Scope
import zio.flow.{FlowId, RemoteVariableName, ZIOFlowBaseSpec}
import zio.flow.serialization.Generators
import zio.test._

object ScopedRemoteVariableNameSpec extends ZIOFlowBaseSpec with Generators {
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
            RemoteVariableScope.Fiber(FlowId("flow2"), RemoteVariableScope.TopLevel(FlowId("flow1")))
          )
          assertInvertible(name)
        }
      )
    ) @@ TestAspect.sized(500) @@ TestAspect.samples(1000)

  private def assertInvertible(name: ScopedRemoteVariableName) =
    assertTrue(
      ScopedRemoteVariableName.fromString(name.asString).get == name
    ) ?? s"encoded form: ${name.asString}"
}
