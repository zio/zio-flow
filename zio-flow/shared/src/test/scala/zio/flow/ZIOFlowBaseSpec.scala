package zio.flow

import zio.ZLayer
import zio.test.{TestEnvironment, ZIOSpecDefault, testEnvironment}

trait ZIOFlowBaseSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] = testEnvironment ++ DumpMetrics.layer
}
