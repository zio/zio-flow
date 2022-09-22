package zio.flow.remote

import zio.flow._

final class RemoteListCompanionSyntax(val self: List.type) extends AnyVal {
  def fill[A](n: Remote[Int])(elem: Remote[A]): Remote[List[A]] =
    Remote
      .recurseSimple((n, Remote.nil[A])) { case (input, rec) =>
        val current = input._1
        val lst     = input._2
        (current === 0).ifThenElse(
          ifTrue = (current, lst),
          ifFalse = (current - 1, elem :: lst)
        )
      }
      ._2
}
