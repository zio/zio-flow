package zio.flow

import zio.flow.Remote.{ Cons, apply }

trait RemoteList[+A] {
  def self: Remote[A]

  def ++[A1](other: Remote[List[A1]])(implicit ev: A <:< List[A1]): Remote[List[A1]] = {
    val reversedSelf: Remote[List[A1]] = self.reverse
    reversedSelf.fold[A1, List[A1]](other)((l, a) => Remote.Cons(l, a))
  }

  def reverse[A0](implicit ev: A <:< List[A0]): Remote[List[A0]] =
    fold[A0, List[A0]](Remote(Nil))((l, a) => Remote.Cons(l, a))

  def take[A1](num: Remote[Int])(implicit ev: A <:< List[A1]): Remote[List[A1]] = {
    val ifTrue = Remote
      .UnCons(self.widen[List[A1]])
      .widen[Option[(A1, List[A1])]]
      .option(Nil, (tuple: Remote[(A1, List[A1])]) => Cons(tuple._2.take[A1](num - Remote(1)), tuple._1))
    (num > Remote(0)).ifThenElse(ifTrue, Nil)
  }

  def takeWhile[A1](predicate: Remote[A1] => Remote[Boolean])(implicit ev: A <:< List[A1]): Remote[List[A1]] =
    Remote
      .UnCons(self.widen[List[A1]])
      .widen[Option[(A1, List[A1])]]
      .option(
        Remote(Nil),
        (tuple: Remote[(A1, List[A1])]) =>
          predicate(tuple._1).ifThenElse(Cons(tuple._2.takeWhile[A1](predicate), tuple._1), Nil)
      )

  def drop[A1](num: Remote[Int])(implicit ev: A <:< List[A1]): Remote[List[A1]] = {
    val ifTrue = Remote
      .UnCons(self.widen[List[A1]])
      .widen[Option[(A1, List[A1])]]
      .option(Nil, (tuple: Remote[(A1, List[A1])]) => tuple._2.drop[A1](num - Remote(1)))

    (num > Remote(0)).ifThenElse(ifTrue, self.widen[List[A1]])
  }

  def dropWhile[A1](predicate: Remote[A1] => Remote[Boolean])(implicit ev: A <:< List[A1]): Remote[List[A1]] =
    Remote
      .UnCons(self.widen[List[A1]])
      .widen[Option[(A1, List[A1])]]
      .option(Nil, (tuple: Remote[(A1, List[A1])]) => tuple._2.dropWhile[A1](predicate))

  final def fold[A0, B](initial: Remote[B])(f: (Remote[B], Remote[A0]) => Remote[B])(implicit
    ev: A <:< List[A0]
  ): Remote[B] =
    Remote.Fold(self.widen[List[A0]], initial, (tuple: Remote[(B, A0)]) => f(tuple._1, tuple._2))

  final def headOption[A1](implicit ev: A <:< List[A1]): Remote[Option[A1]] = Remote
    .UnCons(self.widen[List[A1]])
    .widen[Option[(A1, List[A1])]]
    .option(Remote(None), (tuple: Remote[(A1, List[A1])]) => Remote.Some0(tuple._1))


  final def length[A0](implicit ev: A <:< List[A0]): Remote[Int] =
    self.fold[A0, Int](0)((len, _) => len + 1)

  final def product[A0](implicit ev: A <:< List[A0], numeric: Numeric[A0]): Remote[A0] =
    fold[A0, A0](numeric.fromLong(1L))(_ * _)

  final def sum[A0](implicit ev: A <:< List[A0], numeric: Numeric[A0]): Remote[A0] =
    fold[A0, A0](numeric.fromLong(0L))(_ + _)
}
