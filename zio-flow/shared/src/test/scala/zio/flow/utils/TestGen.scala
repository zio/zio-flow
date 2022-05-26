package zio.flow.utils

import zio.test.Gen

import java.time.Instant
import java.time.temporal.{ChronoField, ChronoUnit}

object TestGen {

  def long: Gen[Any, Long] =
    Gen.long(Int.MinValue.toLong, Int.MaxValue.toLong)

  def instant: Gen[Any, Instant] =
    Gen.instant(Instant.EPOCH, Instant.MAX)

  def chronoField: Gen[Any, ChronoField] =
    Gen.elements(
      ChronoField.SECOND_OF_DAY,
      ChronoField.MINUTE_OF_HOUR,
      ChronoField.HOUR_OF_DAY
    )

  def chronoUnit: Gen[Any, ChronoUnit] =
    Gen.elements(
      ChronoUnit.SECONDS,
      ChronoUnit.MILLIS,
      ChronoUnit.NANOS
    )

}
