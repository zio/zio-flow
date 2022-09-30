package zio.flow

import zio.test.{TestEnvironment, ZIOSpecDefault, testEnvironment}
import zio.{Scope, ZLayer}

trait ZIOFlowBaseSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Scope, Any, TestEnvironment] = testEnvironment ++ DumpMetrics.layer
}
