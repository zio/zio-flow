package zio.flow

trait ExprDuration[+A] {
  def self: Expr[A]

}
