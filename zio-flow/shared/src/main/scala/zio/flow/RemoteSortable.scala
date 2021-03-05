package zio.flow

trait RemoteSortable[+A] {
  def self: Remote[A]

  final def <[A1 >: A: Sortable](that: Remote[A1]): Remote[Boolean] =
    (self <= that) && (self !== that)

  final def <=[A1 >: A: Sortable](that: Remote[A1]): Remote[Boolean] =
    Remote.LessThanEqual(self, that, implicitly[Sortable[A1]])

  final def >[A1 >: A: Sortable](that: Remote[A1]): Remote[Boolean] =
    !(self <= that)

  final def >=[A1 >: A: Sortable](that: Remote[A1]): Remote[Boolean] =
    (self > that) || (self === that)

  final def !==[A1 >: A: Sortable](that: Remote[A1]): Remote[Boolean] =
    !(self === that)

  final def ===[A1 >: A: Sortable](that: Remote[A1]): Remote[Boolean] =
    (self <= that) && (that <= self)
}
