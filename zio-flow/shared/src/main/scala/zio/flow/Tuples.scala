package zio.flow

object Tuples {
  // TODO: Path-dependent types for all arities
  def unapply[A, B](remote: Remote[(A, B)]): Some[(Remote[A], Remote[B])] =
    Some((remote._1, remote._2))
}
