package zio.flow

import java.time.Duration
import java.time.temporal.TemporalUnit

trait ExprDuration[+A] {
  def self: Expr[A]

  def plus(that: Expr[Duration])(implicit ev: A <:< Duration): Expr[Duration] =
    Expr.PlusDuration(self.widen[Duration], that)

  def minus(that: Expr[Duration])(implicit ev: A <:< Duration): Expr[Duration] =
    Expr.MinusDuration(self.widen[Duration], that)

  def get(temporalUnit: TemporalUnit)(implicit ev: A <:< Duration): Expr[Long] =
    Expr.LongDuration(self.widen[Duration], Expr(temporalUnit))
}
