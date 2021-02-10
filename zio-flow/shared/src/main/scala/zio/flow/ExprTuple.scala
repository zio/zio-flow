package zio.flow

trait ExprTuple[+A] {
  def self: Expr[A]

  final def _1[X, Y](implicit ev: A <:< (X, Y)): Expr[X] =
    Expr.First(self.widen[(X, Y)])

  final def _2[X, Y](implicit ev: A <:< (X, Y)): Expr[Y] =
    Expr.Second(self.widen[(X, Y)])

  final def ->[B](that: Expr[B]): Expr[(A, B)] = Expr.tuple2((self, that))
}
