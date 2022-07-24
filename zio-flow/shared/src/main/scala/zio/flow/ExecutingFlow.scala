package zio.flow

import zio.flow.internal.DurablePromise
import zio.schema.Schema

final case class ExecutingFlow[+E, +A](id: FlowId, result: DurablePromise[_, _])

object ExecutingFlow {
  implicit def schema[E, A]: Schema[ExecutingFlow[E, A]] =
    Schema.CaseClass2[String, DurablePromise[Either[Throwable, E], A], ExecutingFlow[E, A]](
      Schema.Field("id", Schema[String]),
      Schema.Field("result", Schema[DurablePromise[Either[Throwable, E], A]]),
      { case (id, promise) => ExecutingFlow(FlowId(id), promise) },
      (ef: ExecutingFlow[E, A]) => FlowId.unwrap(ef.id),
      (ef: ExecutingFlow[E, A]) => ef.result.asInstanceOf[DurablePromise[Either[Throwable, E], A]]
    )
}
