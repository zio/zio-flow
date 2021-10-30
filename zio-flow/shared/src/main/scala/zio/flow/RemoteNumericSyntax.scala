package zio.flow

class RemoteNumericSyntax[A](self: Remote[A]) {

  final def +(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.AddNumeric(self.widen[A], that, numeric)

  final def /(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.DivNumeric(self.widen[A], that, numeric)

  final def *(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.MulNumeric(self.widen[A], that, numeric)

  final def unary_-(implicit numeric: Numeric[A]): Remote[A] =
    Remote.NegationNumeric(self.widen[A], numeric)

  final def -(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.AddNumeric(self.widen[A], -that, numeric)

  final def pow(exp: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.PowNumeric(self.widen[A], exp, numeric)

  final def root(n: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.RootNumeric(self.widen[A], n, numeric)

  final def log(base: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.LogNumeric(self.widen[A], base, numeric)

  final def mod(that: Remote[Int])(implicit ev: A <:< Int, numericInt: Numeric[Int]): Remote[Int] =
    Remote.ModNumeric(self.widen[Int], that)

  final def abs(implicit numeric: Numeric[A]): Remote[A] =
    Remote.AbsoluteNumeric(self.widen[A], numeric)

  final def min(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.MinNumeric(self.widen[A], that, numeric)
}
