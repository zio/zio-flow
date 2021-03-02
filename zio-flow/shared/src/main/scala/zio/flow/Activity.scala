package zio.flow

final case class Activity[-I, +E, A](
  name: String,
  description: String,
  perform: Operation[I, E, A],
  check: Option[Operation[I, E, A]],
  compensate: Operation[A, E, Unit]
)               { self =>
  def apply(input: Expr[I]): ZFlow[Any, E, A] = ZFlow.RunActivity(input, self)

  def apply[I1, I2](i1: Expr[I1], i2: Expr[I2])(implicit ev: (I1, I2) <:< I): ZFlow[Any, E, A] =
    self.narrow[(I1, I2)].apply(Expr.tuple2((i1, i2)))

  def apply[I1, I2, I3](i1: Expr[I1], i2: Expr[I2], i3: Expr[I3])(implicit ev: (I1, I2, I3) <:< I): ZFlow[Any, E, A] =
    self.narrow[(I1, I2, I3)].apply(Expr.tuple3((i1, i2, i3)))

  def apply[I1, I2, I3, I4](i1: Expr[I1], i2: Expr[I2], i3: Expr[I3], i4: Expr[I4])(implicit
    ev: (I1, I2, I3, I4) <:< I
  ): ZFlow[Any, E, A] =
    self.narrow[(I1, I2, I3, I4)].apply(Expr.tuple4((i1, i2, i3, i4)))

  final def narrow[I0](implicit ev: I0 <:< I): Activity[I0, E, A] = {
    val _ = ev

    self.asInstanceOf[Activity[I0, E, A]]
  }
}
object Activity {}
