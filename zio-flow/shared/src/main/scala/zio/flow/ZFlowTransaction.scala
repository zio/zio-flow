package zio.flow

sealed trait ZFlowTransaction {

  /**
   * Suspends the transaction until the variables in the transaction are modified from the outside.
   */
  def retryUntil(predicate: Remote[Boolean]): ZFlow[Any, Nothing, Any]
}

object ZFlowTransaction {
  private[flow] val instance: ZFlowTransaction =
    new ZFlowTransaction {
      def retryUntil(predicate: Remote[Boolean]): ZFlow[Any, Nothing, Any] =
        ZFlow.ifThenElse(predicate)(ZFlow.unit, ZFlow.RetryUntil)
    }
}
