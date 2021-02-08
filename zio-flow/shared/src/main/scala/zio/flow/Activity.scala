package zio.flow

final case class Activity[-I, +E, A](
  name: String,
  description: String,
  perform: Operation[I, E, A],
  check: Option[Operation[I, E, A]],
  compensate: Operation[A, E, Unit]
)               { self =>
  def apply(input: Expr[I]): ZFlow[Any, E, A] = ZFlow.RunActivity(input, self)
}
object Activity {}
