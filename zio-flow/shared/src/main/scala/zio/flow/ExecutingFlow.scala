package zio.flow

import zio.Fiber
import zio.flow.internal.DurablePromise
import zio.schema.Schema

sealed trait ExecutingFlow[+E, +A]

object ExecutingFlow {
  final case class InMemoryExecutingFlow[+E, +A](fiber: Fiber[E, A])                         extends ExecutingFlow[E, A]
  final case class PersistentExecutingFlow[+E, +A](id: String, result: DurablePromise[_, _]) extends ExecutingFlow[E, A]

  object PersistentExecutingFlow {
    def schema[E, A]: Schema[PersistentExecutingFlow[E, A]] =
      (Schema[String] zip Schema[DurablePromise[Either[Throwable, E], A]]).transform(
        { case (id, promise) => PersistentExecutingFlow(id, promise) },
        (ef: PersistentExecutingFlow[E, A]) => (ef.id, ef.result.asInstanceOf[DurablePromise[Either[Throwable, E], A]])
      )
  }

  implicit def schema[E, A]: Schema[ExecutingFlow[E, A]] =
    Schema.Enum1[PersistentExecutingFlow[E, A], ExecutingFlow[E, A]](
      Schema.Case[PersistentExecutingFlow[E, A], ExecutingFlow[E, A]](
        "PersistentExecutingFlow",
        PersistentExecutingFlow.schema[E, A],
        _.asInstanceOf[PersistentExecutingFlow[E, A]]
      )
    )
}
