package zio.flow

trait ExprSortable[+A] {
  def self: Expr[A]

  final def <[A1 >: A: Sortable](that: Expr[A1]): Expr[Boolean] =
    (self <= that) && (self !== that)

  final def <=[A1 >: A: Sortable](that: Expr[A1]): Expr[Boolean] =
    Expr.LessThanEqual(self, that, implicitly[Sortable[A1]])

  final def >[A1 >: A: Sortable](that: Expr[A1]): Expr[Boolean] =
    !(self <= that)

  final def >=[A1 >: A: Sortable](that: Expr[A1]): Expr[Boolean] =
    (self > that) || (self === that)

  final def !==[A1 >: A: Sortable](that: Expr[A1]): Expr[Boolean] =
    !(self === that)

  final def ===[A1 >: A: Sortable](that: Expr[A1]): Expr[Boolean] =
    (self <= that) && (that <= self)
}
