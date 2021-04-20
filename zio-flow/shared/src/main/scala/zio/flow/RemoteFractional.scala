package zio.flow

trait RemoteFractional[+A] {

  def self: Remote[A]

  final def sin[A1 >: A](implicit fractional: Fractional[A1]): Remote[A1] =
    Remote.SinFractional(self, fractional)

  final def cos[A1 >: A](implicit fractional: Fractional[A1]): Remote[A1] = {
    implicit val schemaA1: Schema[A1] = fractional.schema
    Remote(fractional.fromDouble(1.0)) - sin(fractional) pow Remote(fractional.fromDouble(2.0))
  }

  final def tan[A1 >: A](implicit fractional: Fractional[A1]): Remote[A1] =
    sin(fractional) / cos(fractional)

  final def sinInverse[A1 >: A](implicit fractional: Fractional[A1]): Remote[A1] =
    Remote.SinInverseFractional(self, fractional)

  final def cosInverse[A1 >: A](implicit fractional: Fractional[A1]): Remote[A1] = {
    implicit val schema = fractional.schema
    Remote(fractional.fromDouble(1.571)) - sinInverse(fractional)
  }
}
