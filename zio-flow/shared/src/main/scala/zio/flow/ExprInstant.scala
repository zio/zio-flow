package zio.flow

trait ExprInstant[+A] {
  def self: Expr[A]

}
