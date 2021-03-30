package zio.flow

trait ExecutingFlow[+E, +A] {
  def flowId: FlowId

  def await: ZFlow[Any, ActivityError, Either[E, A]]

  def interrupt: ZFlow[Any, ActivityError, Any]
}
