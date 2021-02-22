package zio.flow

import java.time.Duration
import java.time.temporal.{ ChronoUnit, TemporalUnit }

trait ExprDuration[+A] {
  def self: Expr[A]

  def plus(that: Expr[Duration])(implicit ev: A <:< Duration): Expr[Duration] =
    ExprDuration.ofSeconds(self.seconds + that.seconds)

  def minus(that: Expr[Duration])(implicit ev: A <:< Duration): Expr[Duration] =
    ExprDuration.ofSeconds(self.seconds - that.seconds)

  def get(temporalUnit: Expr[TemporalUnit])(implicit ev: A <:< Duration): Expr[Long] =
    Expr.LongDuration(self.widen[Duration], temporalUnit)

  def seconds(implicit ev: A <:< Duration): Expr[Long] = self.get(Expr(ChronoUnit.SECONDS))
}

object ExprDuration {
  def ofSeconds(seconds: Expr[Long]): Expr[Duration] = Expr.SecDuration(seconds)

  def ofMinutes(minutes: Expr[Long]): Expr[Duration] = ExprDuration.ofSeconds(minutes * Expr(60))

  def ofHours(hours: Expr[Long]): Expr[Duration] = ExprDuration.ofMinutes(hours * Expr(60))

  def ofDays(days: Expr[Long]): Expr[Duration] = ExprDuration.ofHours(days * Expr(24))
}
