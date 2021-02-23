package zio.flow

import java.time.Duration
import java.time.temporal.{ ChronoUnit, TemporalUnit }

trait ExprDuration[+A] {
  def self: Expr[A]

  def plus(that: Expr[Duration])(implicit ev: A <:< Duration): Expr[Duration] =
    Expr.ofSeconds(self.seconds + that.seconds)

  def minus(that: Expr[Duration])(implicit ev: A <:< Duration): Expr[Duration] =
    Expr.ofSeconds(self.seconds - that.seconds)

  def get(temporalUnit: Expr[TemporalUnit])(implicit ev: A <:< Duration): Expr[Long] =
    Expr.DurationToLong(self.widen[Duration], temporalUnit)

  def seconds(implicit ev: A <:< Duration): Expr[Long] = self.get(Expr(ChronoUnit.SECONDS))
}
