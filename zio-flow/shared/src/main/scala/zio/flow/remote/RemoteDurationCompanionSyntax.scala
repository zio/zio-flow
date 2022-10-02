package zio.flow.remote

import zio.Duration
import zio.flow.Remote

import java.time.Instant
import java.time.temporal.ChronoUnit

final class RemoteDurationCompanionSyntax(val self: Duration.type) {
  def between(startInclusive: Remote[Instant], endExclusive: Remote[Instant]): Remote[Duration] =
    Remote.DurationBetweenInstants(startInclusive, endExclusive)

  def of(amount: Remote[Long], unit: Remote[ChronoUnit]): Remote[Duration] =
    Remote.DurationFromAmount(amount, unit)

  def ofDays(days: Remote[Long]): Remote[Duration] =
    of(days, Remote(ChronoUnit.DAYS))

  def ofHours(hours: Remote[Long]): Remote[Duration] =
    of(hours, Remote(ChronoUnit.HOURS))

  def ofMillis(millis: Remote[Long]): Remote[Duration] =
    of(millis, Remote(ChronoUnit.MILLIS))

  def ofMinutes(minutes: Remote[Long]): Remote[Duration] =
    of(minutes, Remote(ChronoUnit.MINUTES))

  def ofNanos(nanos: Remote[Long]): Remote[Duration] =
    of(nanos, Remote(ChronoUnit.NANOS))

  def ofSeconds(seconds: Remote[Long]): Remote[Duration] =
    of(seconds, Remote(ChronoUnit.SECONDS))

  def ofSeconds(seconds: Remote[Long], nanos: Remote[Long]): Remote[Duration] =
    ofSeconds(seconds).plusNanos(nanos)

  private[flow] def ofSecondsBigDecimal(seconds: Remote[BigDecimal]): Remote[Duration] =
    Remote.Unary(seconds, UnaryOperators.Conversion(RemoteConversions.BigDecimalToDuration))

  def parse(text: Remote[String]): Remote[Duration] =
    Remote.Unary(text, UnaryOperators.Conversion(RemoteConversions.StringToDuration))
}
