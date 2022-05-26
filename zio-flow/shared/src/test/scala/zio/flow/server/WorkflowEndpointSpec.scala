package zio.flow.server

import zio.{Scope, ZLayer}
import zio.flow.ZIOFlowBaseSpec
import zio.flow.utils.MockExecutors
import zio.test.{Spec, TestEnvironment, assertTrue}

object WorkflowEndpointSpec extends ZIOFlowBaseSpec {

  override def spec: Spec[TestEnvironment with Scope, Any] = suite("TODO")(
    test("TODO")(assertTrue(true))
  )

  val exec = ZLayer.fromZIO(MockExecutors.mockPersistentTestClock)

  val endpointLayer = exec >+> ZLayer.fromZIO(WorkflowEndpoint.make())

}
