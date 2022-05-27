package zio.flow.internal

import zio.flow.FlowId

object Topics {
  def promise(promiseId: String): String =
    s"_zflow_durable_promise__$promiseId"

  def variableChanges(flowId: FlowId): String =
    s"_zflow_variable_changes__${FlowId.unwrap(flowId)}"
}
