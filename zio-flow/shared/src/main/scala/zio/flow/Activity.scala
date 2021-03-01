package zio.flow

final case class Activity[-I, +E, A](
  name: String,
  description: String,
  perform: Operation[I, E, A],
  check: Option[Operation[I, E, A]],
  compensate: Operation[A, E, Unit]
)               { self =>
  def apply(input: Expr[I]): ZFlow[Any, E, A] = ZFlow.RunActivity(input, self)

  def apply[I1, I2](i1: Expr[I1], i2: Expr[I2])(implicit ev: I <:< (I1, I2)): ZFlow[Any, E, A] = {
    val _ = ev 
    
    apply(Expr.tuple2(i1, i2).asInstanceOf[Expr[I]])
  }
}
object Activity {}
