package zio.flow

import zio.flow.Remote.{ Cons, apply }

class RemoteListSyntax[A](val self: Remote[List[A]]) {

  def ++(other: Remote[List[A]]): Remote[List[A]] = {
    val reversedSelf: Remote[List[A]] = reverse
    reversedSelf.fold(other)((l, a) => Remote.Cons(l, a))
  }

  def reverse: Remote[List[A]] =
    fold(Remote(Nil))((l, a) => Remote.Cons(l, a))

  def take(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue: Remote[List[A]] = Remote
      .UnCons(self.widen[List[A]])
      .widen[Option[(A, List[A])]]
      .handleOption(Nil, (tuple: Remote[(A, List[A])]) => Cons(tuple._2.take(num - Remote(1)), tuple._1))
    (num > Remote(0)).ifThenElse(ifTrue, Nil)
  }

  def takeWhile(predicate: Remote[A] => Remote[Boolean]): Remote[List[A]] =
    Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .handleOption(
        Remote(Nil),
        (tuple: Remote[(A, List[A])]) =>
          predicate(tuple._1).ifThenElse(Cons(tuple._2.takeWhile(predicate), tuple._1), Nil)
      )

  def drop(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue = Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .handleOption(Nil, (tuple: Remote[(A, List[A])]) => tuple._2.drop(num - Remote(1)))

    (num > Remote(0)).ifThenElse(ifTrue, self)
  }

  def dropWhile(predicate: Remote[A] => Remote[Boolean]): Remote[List[A]] =
    Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .handleOption(Nil, (tuple: Remote[(A, List[A])]) => tuple._2.dropWhile(predicate))

  final def fold[B](initial: Remote[B])(f: (Remote[B], Remote[A]) => Remote[B]): Remote[B] =
    Remote.Fold(self, initial, (tuple: Remote[(B, A)]) => f(tuple._1, tuple._2))

  final def headOption1: Remote[Option[A]] = Remote
    .UnCons(self)
    .widen[Option[(A, List[A])]]
    .handleOption(Remote(None), (tuple: Remote[(A, List[A])]) => Remote.Some0(tuple._1))

  final def headOption: Remote[Option[A]] =
    fold(Remote(None))((remoteOptionA, a) => remoteOptionA.isSome.ifThenElse(remoteOptionA.self, Remote.Some0(a)))

  final def length[A0](implicit ev: A <:< List[A0]): Remote[Int] =
    self.fold[A0, Int](0)((len, _) => len + 1)

  final def product[A0](implicit ev: A <:< List[A0], numeric: Numeric[A0]): Remote[A0] =
    fold[A0, A0](numeric.fromLong(1L))(_ * _)

  final def sum[A0](implicit ev: A <:< List[A0], numeric: Numeric[A0]): Remote[A0] =
    fold[A0, A0](numeric.fromLong(0L))(_ + _)

  final def filter[A0](predicate: Remote[A0] => Remote[Boolean])(implicit ev: A <:< List[A0]): Remote[List[A0]] =
    self.fold[A0, List[A0]](Nil)((a2: Remote[List[A0]], a1: Remote[A0]) => predicate(a1).ifThenElse(Cons(a2, a1), a2))

  final def isEmpty[A0](implicit ev: A <:< List[A0]): Remote[Boolean] =
    self.headOption.isNone.ifThenElse(Remote(true), Remote(false))
}
