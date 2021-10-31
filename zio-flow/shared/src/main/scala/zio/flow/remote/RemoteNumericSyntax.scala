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

  final def abs(implicit numeric: Numeric[A]): Remote[A] =
    Remote.AbsoluteNumeric(self.widen[A], numeric)

  final def min(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.MinNumeric(self.widen[A], that, numeric)

  final def max(that: Remote[A])(implicit numeric: Numeric[A]): Remote[A] =
    Remote.MaxNumeric(self.widen[A], that, numeric)

  final def floor(implicit numeric: Numeric[A]): Remote[A] =
    Remote.FloorNumeric(self.widen[A], numeric)

  final def ceil(implicit numeric: Numeric[A]): Remote[A] =
    Remote.CeilNumeric(self.widen[A], numeric)

  final def round(implicit numeric: Numeric[A]): Remote[A] =
    Remote.RoundNumeric(self.widen[A], numeric)
}
