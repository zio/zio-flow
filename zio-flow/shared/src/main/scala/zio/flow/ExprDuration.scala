package zio.flow

import java.time.temporal.{ ChronoUnit, TemporalUnit }
import java.time.{ Duration, Instant }

trait ExprDuration[+A] {
  def self: Expr[A]

  def plus(that: Expr[Duration])(implicit ev: A <:< Duration): Expr[Duration] =
    Expr.PlusDuration(self.widen[Duration], that)

  def minus(that: Expr[Duration])(implicit ev: A <:< Duration): Expr[Duration] =
    Expr.MinusDuration(self.widen[Duration], that)

  def get(temporalUnit: TemporalUnit)(implicit ev: A <:< Instant): Expr[Long] =
    Expr.LongInstant(self.widen[Instant], Expr(temporalUnit))
}
