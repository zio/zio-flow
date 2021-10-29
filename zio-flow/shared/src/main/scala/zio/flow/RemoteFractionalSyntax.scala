package zio.flow

import zio.schema.Schema

class RemoteFractionalSyntax[A](self: Remote[A]) {

  final def sin(implicit fractional: Fractional[A]): Remote[A] =
    Remote.SinFractional(self, fractional)

  final def cos(implicit fractional: Fractional[A]): Remote[A] = {
    implicit val schemaA1: Schema[A] = fractional.schema
    Remote(fractional.fromDouble(1.0)) - sin(fractional) pow Remote(fractional.fromDouble(2.0))
  }

  final def tan(implicit fractional: Fractional[A]): Remote[A] =
    sin(fractional) / cos(fractional)

  final def sinInverse(implicit fractional: Fractional[A]): Remote[A] =
    Remote.SinInverseFractional(self, fractional)

  final def cosInverse(implicit fractional: Fractional[A]): Remote[A] = {
    implicit val schema = fractional.schema
    Remote(fractional.fromDouble(1.571)) - sinInverse(fractional)
  }

  final def tanInverse(implicit fractional: Fractional[A]): Remote[A] = ???
}
