package zio.flow

trait RemoteList[+A] {
  def self: Remote[A]

  def ++[A1](other: Remote[List[A1]])(implicit ev: A <:< List[A1]): Remote[List[A1]] = ???

  final def fold[A0, B](initial: Remote[B])(f: (Remote[B], Remote[A0]) => Remote[B])(implicit
    ev: A <:< List[A0]
  ): Remote[B] =
    Remote.Fold(self.widen[List[A0]], initial, (tuple: Remote[(B, A0)]) => f(tuple._1, tuple._2))

  final def headOption[A1](implicit ev: A <:< List[A1]): Remote[Option[A1]] = (Remote
    .UnCons(self.widen[List[A1]])
    .widen[Option[(A1, List[A1])]])
    .option(Remote(None), (tuple: Remote[(A1, List[A1])]) => Remote.Some0(tuple._1))

  final def length[A0](implicit ev: A <:< List[A0]): Remote[Int] =
    self.fold[A0, Int](0)((len, _) => len + 1)

  final def product[A0](implicit ev: A <:< List[A0], numeric: Numeric[A0]): Remote[A0] =
    fold[A0, A0](numeric.fromLong(1L))(_ * _)

  final def sum[A0](implicit ev: A <:< List[A0], numeric: Numeric[A0]): Remote[A0] =
    fold[A0, A0](numeric.fromLong(0L))(_ + _)
}
