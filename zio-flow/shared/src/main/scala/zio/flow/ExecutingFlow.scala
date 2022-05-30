package zio.flow

import zio.flow.internal.DurablePromise
import zio.schema.Schema

final case class ExecutingFlow[+E, +A](id: FlowId, result: DurablePromise[_, _])

object ExecutingFlow {
  implicit def schema[E, A]: Schema[ExecutingFlow[E, A]] =
    (Schema[String] zip Schema[DurablePromise[Either[Throwable, E], A]]).transform(
      { case (id, promise) => ExecutingFlow(FlowId(id), promise) },
      (ef: ExecutingFlow[E, A]) =>
        (FlowId.unwrap(ef.id), ef.result.asInstanceOf[DurablePromise[Either[Throwable, E], A]])
    )
}
