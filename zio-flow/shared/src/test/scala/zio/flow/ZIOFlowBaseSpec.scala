package zio.flow

import zio.test._

import zio._
import zio.test.{Live, ZIOSpecDefault}

trait ZIOFlowBaseSpec extends ZIOSpecDefault {
  override def aspects: Chunk[TestAspectAtLeastR[Live]] =
    Chunk(
//      TestAspect.timeout(60.seconds)
    )
}
