package zio.flow

import zio.flow.internal.DurablePromise
import zio.schema.Schema

final case class ExecutingFlow[+E, +A](id: String, result: DurablePromise[_, _])

object ExecutingFlow {
  implicit def schema[E, A]: Schema[ExecutingFlow[E, A]] =
    (Schema[String] zip Schema[DurablePromise[E, A]]).transform(
      { case (id, promise) => ExecutingFlow(id, promise) },
      (ef: ExecutingFlow[E, A]) => (ef.id, ef.result.asInstanceOf[DurablePromise[E, A]])
    )
}
