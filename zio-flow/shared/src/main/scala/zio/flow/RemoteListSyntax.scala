package zio.flow

import zio.flow.Remote.{ Cons, apply }

class RemoteListSyntax[A](val self: Remote[List[A]]) {

  def ++(other: Remote[List[A]]): Remote[List[A]] =
    self.concat(other)

  def concat(that: Remote[List[A]]): Remote[List[A]] =
    reverse.fold(that)(Remote.Cons(_, _))

  def contains(elem: Remote[A]): Remote[Boolean] =
    Remote
      .UnCons(self.widen[List[A]])
      .widen[Option[(A, List[A])]]
      .handleOption(
        false,
        tuple => (tuple._1 === elem) || tuple._2.contains(elem)
      )

  def containsSlice(slice: Remote[List[A]]): Remote[Boolean] =
    Remote
      .UnCons(self.widen[List[A]])
      .widen[Option[(A, List[A])]]
      .handleOption(
        slice.isEmpty || self.startsWith(slice),
        tuple => tuple._2.containsSlice(slice)
      )

  def drop(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue =
      Remote
        .UnCons(self)
        .widen[Option[(A, List[A])]]
        .handleOption(Nil, (tuple: Remote[(A, List[A])]) => tuple._2.drop(num - Remote(1)))

    (num > Remote(0)).ifThenElse(ifTrue, self)
  }

  def dropWhile(predicate: Remote[A] => Remote[Boolean]): Remote[List[A]] =
    Remote
      .UnCons(self)
      .widen[Option[(A, List[A])]]
      .handleOption(Remote(Nil), (tuple: Remote[(A, List[A])]) => tuple._2.dropWhile(predicate))

  def endsWith[B >: A](suffix: Remote[List[B]]): Remote[Boolean] =
    reverse.startsWith(suffix.reverse)

  final def filter(predicate: Remote[A] => Remote[Boolean]): Remote[List[A]] =
    fold[List[A]](Remote(Nil))((a2: Remote[List[A]], a1: Remote[A]) => predicate(a1).ifThenElse(Cons(a2, a1), a2))

  final def fold[B](initial: Remote[B])(f: (Remote[B], Remote[A]) => Remote[B]): Remote[B] =
    Remote.Fold(self, initial, (tuple: Remote[(B, A)]) => f(tuple._1, tuple._2))

  def forAll(p: Remote[A] => Remote[Boolean]): Remote[Boolean] =
    fold(true)((b, a) => p(a) && b)

  final def headOption1: Remote[Option[A]] = Remote
    .UnCons(self)
    .widen[Option[(A, List[A])]]
    .handleOption[Option[A]](Remote(None), tuple => Remote.Some0(tuple._1))

  final def headOption: Remote[Option[A]] =
    fold[Option[A]](Remote(None))((remoteOptionA, a) =>
      remoteOptionA.isSome.ifThenElse(remoteOptionA.self, Remote.Some0(a))
    )

  def indexOf(elem: Remote[A], from: Remote[Int] = 0): Remote[Int] =
    indexWhere(elem === _, from)

  // can be optimized with the KMP algorithm, but we don't have a remote indexed sequence yet
  def indexOfSlice[B >: A](that: Remote[List[B]], from: Remote[Int] = 0): Remote[Int] = {
    def loop(list: Remote[List[B]], i: Remote[Int], acc: Remote[Int]): Remote[Int] =
      Remote
        .UnCons(list.widen[List[B]])
        .widen[Option[(B, List[B])]]
        .handleOption(
          acc,
          tuple => list.startsWith(that).ifThenElse(i, loop(tuple._2, i + 1, acc))
        )

    val index = loop(self.drop(from), 0, -1)
    (index === -1).ifThenElse(index, index + from)
  }

  def indexWhere[B >: A](p: Remote[B] => Remote[Boolean], from: Remote[Int] = 0): Remote[Int] = {
    def loop(list: Remote[List[B]], i: Remote[Int], acc: Remote[Int]): Remote[Int] =
      Remote
        .UnCons(list.widen[List[B]])
        .widen[Option[(B, List[B])]]
        .handleOption(
          acc,
          tuple => p(tuple._1).ifThenElse(i, loop(tuple._2, i + 1, acc))
        )

    val index = loop(self.drop(from), 0, -1)
    (index === -1).ifThenElse(index, index + from)
  }

  final def isEmpty: Remote[Boolean] =
    self.headOption.isNone

  def lastIndexOf(elem: Remote[A], end: Remote[Int] = length - 1): Remote[Int] =
    lastIndexWhere(elem === _, end)

  def lastIndexWhere[B >: A](p: Remote[B] => Remote[Boolean], end: Remote[Int] = length - 1): Remote[Int] = {
    val lastIndex = reverse.indexWhere(p, length - end - 1)
    (lastIndex === -1).ifThenElse(lastIndex, length - lastIndex - 1)
  }

  def lastOption: Remote[Option[A]] =
    reverse.headOption

  final def length: Remote[Int] =
    self.fold[Int](0)((len, _) => len + 1)

  final def product(implicit numeric: Numeric[A]): Remote[A] =
    fold[A](numeric.fromLong(1L))(_ * _)

  def reverse: Remote[List[A]] =
    fold[List[A]](Remote(Nil))((l, a) => Remote.Cons(l, a))

  def startsWith[B >: A](prefix: Remote[List[B]]): Remote[Boolean] = {
    val a = Remote.UnCons(self.widen[List[B]]).widen[Option[(B, List[B])]]
    val b = Remote.UnCons(prefix.widen[List[B]]).widen[Option[(B, List[B])]]
    b.isNone.ifThenElse(
      true,
      a.zip(b)
        .handleOption(
          false,
          bothCons => {
            val (h, t, h2, t2) = (bothCons._1._1, bothCons._1._2, bothCons._2._1, bothCons._2._2)
            h === h2 && t.startsWith(t2)
          }
        )
    )
  }

  final def sum(implicit numeric: Numeric[A]): Remote[A] =
    fold[A](numeric.fromLong(0L))(_ + _)

  def take(num: Remote[Int]): Remote[List[A]] = {
    val ifTrue: Remote[List[A]] =
      Remote
        .UnCons(self.widen[List[A]])
        .widen[Option[(A, List[A])]]
        .handleOption(Nil, (tuple: Remote[(A, List[A])]) => Cons(tuple._2.take(num - Remote(1)), tuple._1))
    (num > 0).ifThenElse(ifTrue, Nil)
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
}
