package zio.flow

import zio.ZIO

sealed trait Activity[-I, +E, +A] { self =>
  def run(input: Expr[I]): Workflow[Any, E, A] = Workflow.RunActivity(input, self)
}
object Activity                   {
  final case class Effect[I, E, A](
    uniqueIdentifier: String,
    effect: I => ZIO[Any, E, A],
    completed: ZIO[Any, E, Boolean],
    compensation: A => ZIO[Any, E, Unit],
    description: String
  ) extends Activity[I, E, A]
}
