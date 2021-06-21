package zio.flow

import zio.flow.PolicyRenewalExample.{Policy, sendReminderEmailToEvaluator}

class GoodCoverUseCase {
  def manualEvalReminderFlow(
                              name: Remote[String],
                              printName: RemoteVariable[Boolean]
                            ): ZFlow[Any, ActivityError, Any] =
    ZFlow.doWhile {
      for {
        option <- printName.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
        loop   <- option.isNone.toFlow
      } yield loop
    }
}
