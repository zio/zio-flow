package zio.flow.utils

import zio.Random
import zio.test.Gen

import java.time.Instant
import java.time.temporal.{ChronoField, ChronoUnit}

object TestGen {

  def long: Gen[Random, Long] =
    Gen.long(Int.MinValue.toLong, Int.MaxValue.toLong)

  def instant: Gen[Random, Instant] =
    Gen.instant(Instant.EPOCH, Instant.MAX)

  def chronoField: Gen[Random, ChronoField] =
    Gen.elements(
      ChronoField.SECOND_OF_DAY,
      ChronoField.MINUTE_OF_HOUR,
      ChronoField.HOUR_OF_DAY
    )

  def chronoUnit: Gen[Random, ChronoUnit] =
    Gen.elements(
      ChronoUnit.SECONDS,
      ChronoUnit.MILLIS,
      ChronoUnit.NANOS
    )

}
