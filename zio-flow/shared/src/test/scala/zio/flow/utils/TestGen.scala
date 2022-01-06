package zio.flow.utils

import zio.random.Random
import zio.test.Gen

import java.time.Instant

object TestGen {

  def long: Gen[Random, Long] =
    Gen.long(Int.MinValue.toLong, Int.MaxValue.toLong)

  def instant: Gen[Random, Instant] =
    Gen.instant(Instant.EPOCH, Instant.MAX)
}
