package zio.flow

import java.time.Instant
import java.time.temporal._

trait ExprInstant[+A] {
  def self: Expr[A]

  def getLong(temporalUnit: Expr[TemporalUnit])(implicit ev: A <:< Instant): Expr[Long] =
    Expr.LongInstant(self.widen[Instant], temporalUnit)

  def isAfter(that: Expr[Instant])(implicit ev: A <:< Instant): Expr[Boolean] = Expr.IsAfter(self.widen[Instant], that)

  def isBefore(that: Expr[Instant])(implicit ev: A <:< Instant): Expr[Boolean] = !((isAfter(that)) || (self == that))

  def getEpochSec(implicit ev: A <:< Instant): Expr[Long] =
    Expr.LongInstant(self.widen[Instant], Expr(ChronoUnit.SECONDS))
}
