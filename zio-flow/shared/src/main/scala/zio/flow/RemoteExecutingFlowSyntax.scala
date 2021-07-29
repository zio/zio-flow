package zio.flow

class RemoteExecutingFlowSyntax[A](self: Remote[A]) {

  def flowId[E, A2](implicit ev: A <:< ExecutingFlow[E, A2]): Remote[FlowId] = ???

  def await[E, A2](implicit ev: A <:< ExecutingFlow[E, A2]): ZFlow[Any, ActivityError, Either[E, A2]] =
    ZFlow.Await(self.widen[ExecutingFlow[E, A2]])

  def interrupt[E, A2](implicit ev: A <:< ExecutingFlow[E, A2]): ZFlow[Any, ActivityError, Any] =
    ZFlow.Interrupt(self.widen[ExecutingFlow[E, A2]])

}
