package zio.flow

object Tuples {
  // TODO: Path-dependent types for all arities
  def unapply[A, B](expr: Expr[(A, B)]): Some[(Expr[A], Expr[B])] =
    Some((expr._1, expr._2))
}
