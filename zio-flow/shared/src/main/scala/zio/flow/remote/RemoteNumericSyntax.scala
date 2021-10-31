package zio.flow.remote

import zio.flow.remote

class RemoteNumericSyntax[A](self: Remote[A]) {

  final def +(that: Remote[A])(implicit numeric: remote.Numeric[A]): Remote[A] =
    Remote.AddNumeric(self.widen[A], that, numeric)

  final def /(that: Remote[A])(implicit numeric: remote.Numeric[A]): Remote[A] =
    Remote.DivNumeric(self.widen[A], that, numeric)

  final def *(that: Remote[A])(implicit numeric: remote.Numeric[A]): Remote[A] =
    Remote.MulNumeric(self.widen[A], that, numeric)

  final def unary_-(implicit numeric: remote.Numeric[A]): Remote[A] =
    Remote.NegationNumeric(self.widen[A], numeric)

  final def -(that: Remote[A])(implicit numeric: remote.Numeric[A]): Remote[A] =
    Remote.AddNumeric(self.widen[A], -that, numeric)

  final def pow(exp: Remote[A])(implicit numeric: remote.Numeric[A]): Remote[A] =
    Remote.PowNumeric(self.widen[A], exp, numeric)

  final def root(n: Remote[A])(implicit numeric: remote.Numeric[A]): Remote[A] =
    Remote.RootNumeric(self.widen[A], n, numeric)

  final def log(base: Remote[A])(implicit numeric: remote.Numeric[A]): Remote[A] =
    Remote.LogNumeric(self.widen[A], base, numeric)

  final def mod(that: Remote[Int])(implicit ev: A <:< Int, numericInt: remote.Numeric[Int]): Remote[Int] =
    Remote.ModNumeric(self.widen[Int], that)
}
