package zio.flow

trait RemoteNumeric[+A] {
  def self: Remote[A]

  final def +[A1 >: A](that: Remote[A1])(implicit numeric: Numeric[A1]): Remote[A1] =
    Remote.AddNumeric(self.widen[A1], that, numeric)

  final def /[A1 >: A](that: Remote[A1])(implicit numeric: Numeric[A1]): Remote[A1] =
    Remote.DivNumeric(self.widen[A1], that, numeric)

  final def *[A1 >: A](that: Remote[A1])(implicit numeric: Numeric[A1]): Remote[A1] =
    Remote.MulNumeric(self.widen[A1], that, numeric)

  final def unary_-[A1 >: A](implicit numeric: Numeric[A1]): Remote[A1] =
    Remote.NegationNumeric(self.widen[A1], numeric)

  final def -[A1 >: A](that: Remote[A1])(implicit numeric: Numeric[A1]): Remote[A1] =
    Remote.AddNumeric(self.widen[A1], -that, numeric)

  final def pow[A1 >: A](exp: Remote[A1])(implicit numeric: Numeric[A1]): Remote[A1] =
    Remote.PowNumeric(self.widen[A1], exp, numeric)

  final def root[A1 >: A](n: Remote[A1])(implicit numeric: Numeric[A1]): Remote[A1] =
    Remote.RootNumeric(self.widen[A1], n, numeric)

  final def log[A1 >: A](base: Remote[A1])(implicit numeric: Numeric[A1]): Remote[A1] =
    Remote.LogNumeric(self.widen[A1], base, numeric)
}
