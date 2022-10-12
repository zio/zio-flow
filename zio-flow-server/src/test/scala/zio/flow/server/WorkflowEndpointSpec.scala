package zio.flow.server

import zio.flow.MockExecutors
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}
import zio.{Scope, ZLayer}

object WorkflowEndpointSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment with Scope, Any] = suite("TODO")(
    test("TODO")(assertTrue(true))
  )

  val exec = ZLayer.fromZIO(MockExecutors.persistent())

  val endpointLayer = exec >+> ZLayer.fromZIO(WorkflowEndpoint.make())

}
