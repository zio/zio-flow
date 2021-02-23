package zio.flow

trait ExprList[+A] {
  def self: Expr[A]

  def ++[A1](other: Expr[List[A1]])(implicit ev: A <:< List[A1]): Expr[List[A1]] = ???

  final def fold[A0, B](initial: Expr[B])(f: (Expr[B], Expr[A0]) => Expr[B])(implicit ev: A <:< List[A0]): Expr[B] =
    Expr.Fold(self.widen[List[A0]], initial, (tuple: Expr[(B, A0)]) => f(tuple._1, tuple._2))

  final def headOption[A1](implicit ev: A <:< List[A1]): Expr[Option[A1]] = (Expr
    .UnCons(self.widen[List[A1]])
    .widen[Option[(A1, List[A1])]])
    .option(Expr(None), (tuple: Expr[(A1, List[A1])]) => Expr.Some(tuple._1))

  final def length[A0](implicit ev: A <:< List[A0]): Expr[Int] =
    self.fold[A0, Int](0)((len, _) => len + 1)

  final def product[A0](implicit ev: A <:< List[A0], numeric: Numeric[A0]): Expr[A0] =
    fold[A0, A0](numeric.fromLong(1L))(_ * _)

  final def sum[A0](implicit ev: A <:< List[A0], numeric: Numeric[A0]): Expr[A0] =
    fold[A0, A0](numeric.fromLong(0L))(_ + _)
}
