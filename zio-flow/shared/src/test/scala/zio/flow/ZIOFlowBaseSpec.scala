package zio.flow

import zio.duration._
import zio.test._
import zio.test.environment._

trait ZIOFlowBaseSpec extends DefaultRunnableSpec {
  override def aspects: List[TestAspectAtLeastR[Live]] =
    List(TestAspect.timeout(60.seconds))
}
