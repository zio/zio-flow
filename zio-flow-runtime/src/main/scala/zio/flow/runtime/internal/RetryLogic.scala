package zio.flow.runtime.internal

import zio.Schedule.forever
import zio.flow.ActivityError
import zio.flow.operation.http.{HttpFailure, HttpMethod}
import zio.flow.runtime.operation.http.{HttpOperationPolicy, HttpRetryCondition, Repetition, RetryLimit}
import zio._
import zio.flow.runtime.internal.RetryLogic.{
  conditionToSchedule,
  optionallyJittered,
  repetitionToSchedule,
  retryLimitToSchedule
}

class RetryLogic(policy: HttpOperationPolicy, semaphore: Semaphore, circuitBreaker: CircuitBreaker) {
  private val schedule =
    policy.retryPolicies.map { retryPolicy =>
      conditionToSchedule(retryPolicy.condition) &&
      optionallyJittered(retryPolicy.policy.jitter, repetitionToSchedule(retryPolicy.policy.repeatWith)) &&
      retryLimitToSchedule(retryPolicy.policy.failAfter) &&
      triggerCircuitBreakerIfNeeded(retryPolicy.breakCircuit)
    }.foldLeft[Schedule[Any, Cause[HttpFailure], Any]](Schedule.stop)(_ || _)

  def apply[R, A](method: HttpMethod, host: String)(
    operation: ZIO[R, Cause[HttpFailure], A]
  ): ZIO[R, ActivityError, A] =
    semaphore.withPermit {
      ZIO
        .fail(HttpFailure.CircuitBreakerOpen)
        .sandbox
        .whenZIO(circuitBreaker.isOpen)
        .zipRight(operation)
        .zipLeft(circuitBreaker.onSuccess())
        .retry(schedule)
    }.mapError { e =>
      e.failureOption.map(_.toActivityError(method, host)).getOrElse {
        ActivityError(s"$method request to $host died", Some(FiberFailure(e)))
      }
    }.timeoutFail(ActivityError(s"$method request to $host timed out", None))(
      policy.timeout
    )

  private def triggerCircuitBreakerIfNeeded(enabled: Boolean) =
    if (enabled) {
      Schedule.identity[Cause[HttpFailure]].tapInput((_: Cause[HttpFailure]) => circuitBreaker.onFailure())
    } else {
      Schedule.identity[Cause[HttpFailure]]
    }
}

object RetryLogic {
  def make(policy: HttpOperationPolicy): ZIO[Any, Nothing, RetryLogic] =
    for {
      semaphore <- Semaphore.make(policy.maxParallelRequestCount.toLong)
      circuitBreaker <-
        policy.circuitBreakerPolicy match {
          case Some(resetPolicy) => CircuitBreaker.make(resetPolicy)
          case None              => CircuitBreaker.disabled
        }
    } yield new RetryLogic(policy, semaphore, circuitBreaker)

  private[internal] def conditionToSchedule(condition: HttpRetryCondition): Schedule[Any, Cause[HttpFailure], Any] =
    condition match {
      case HttpRetryCondition.Always =>
        Schedule.forever
      case HttpRetryCondition.ForSpecificStatus(status) =>
        Schedule.recurWhile[Cause[HttpFailure]] {
          case Cause.Fail(HttpFailure.Non200Response(responseStatus), _) if responseStatus.code == status => true
          case _                                                                                          => false
        }
      case HttpRetryCondition.For4xx =>
        Schedule.recurWhile[Cause[HttpFailure]] {
          case Cause.Fail(HttpFailure.Non200Response(responseStatus), _) if responseStatus.isClientError => true
          case _                                                                                         => false
        }
      case HttpRetryCondition.For5xx =>
        Schedule.recurWhile[Cause[HttpFailure]] {
          case Cause.Fail(HttpFailure.Non200Response(responseStatus), _) if responseStatus.isServerError => true
          case _                                                                                         => false
        }
      case HttpRetryCondition.OpenCircuitBreaker =>
        Schedule.recurWhile[Cause[HttpFailure]] {
          case Cause.Fail(HttpFailure.CircuitBreakerOpen, _) => true
          case _                                             => false
        }
      case HttpRetryCondition.Or(first, second) =>
        conditionToSchedule(first) || conditionToSchedule(second)
    }

  private[internal] def repetitionToSchedule(repetition: Repetition) =
    repetition match {
      case Repetition.Fixed(interval) =>
        Schedule.fixed(interval)
      case Repetition.Exponential(base, factor, max) =>
        Schedule.delayed[Any, Any](forever.map { i =>
          val current = base * math.pow(factor, i.doubleValue)
          if (current > max) max else current
        })

    }

  private[internal] def retryLimitToSchedule(limit: RetryLimit) =
    limit match {
      case RetryLimit.ElapsedTime(duration)  => Schedule.duration(duration)
      case RetryLimit.NumberOfRetries(count) => Schedule.recurs(count)
    }

  private[internal] def optionallyJittered[Env, In, Out](jittered: Boolean, schedule: Schedule[Env, In, Out]) =
    if (jittered) schedule.jittered else schedule

}
