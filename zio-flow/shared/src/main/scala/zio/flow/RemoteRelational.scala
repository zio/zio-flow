package zio.flow

trait RemoteRelational[+A] {
  def self: Remote[A]

  final def <[A1 >: A](that: Remote[A1]): Remote[Boolean] =
    (self <= that) && (self !== that)

  final def <=[A1 >: A](that: Remote[A1]): Remote[Boolean] =
    Remote.LessThanEqual(self, that)

  final def >[A1 >: A](that: Remote[A1]): Remote[Boolean] =
    !(self <= that)

  final def >=[A1 >: A](that: Remote[A1]): Remote[Boolean] =
    (self > that) || (self === that)

  final def !==[A1 >: A](that: Remote[A1]): Remote[Boolean] =
    !(self === that)

  final def ===[A1 >: A](that: Remote[A1]): Remote[Boolean] =
    Remote.Equal(self, that)
}
