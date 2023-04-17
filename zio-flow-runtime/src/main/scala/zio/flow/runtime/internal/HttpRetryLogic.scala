package zio.flow.runtime.internal

import zio.Schedule.forever
import zio.flow.ActivityError
import zio.flow.operation.http.{HttpFailure, HttpMethod}
import zio.flow.runtime.operation.http.{HttpOperationPolicy, HttpRetryCondition, Repetition, RetryLimit}
import zio._
import zio.flow.runtime.internal.HttpRetryLogic.{
  conditionToSchedule,
  optionallyJittered,
  repetitionToSchedule,
  retryLimitToSchedule
}
import zio.flow.runtime.metrics
import zio.http.Status

class HttpRetryLogic(policy: HttpOperationPolicy, semaphore: Semaphore, circuitBreaker: CircuitBreaker) {
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
        .either
        .timed
        .flatMap {
          case (duration, Left(failure)) => ZIO.fail((duration, failure))
          case (duration, Right(value))  => ZIO.succeed((duration, value))
        }
        .flatMapError { case (duration, failure) =>
          observeFailure(host, failure, Some(duration), isFinal = false).as(failure)
        }
        .flatMap { case (duration, result) =>
          observeSuccess(host, duration).as(result)
        }
        .retry(observeRetries(host, schedule))
        .tapError(failure => observeFailure(host, failure, None, isFinal = true))
    }.mapError { e =>
      e.failureOption.map(_.toActivityError(method, host)).getOrElse {
        ActivityError(s"$method request to $host died", Some(FiberFailure(e)))
      }
    }.timeoutTo(
      metrics.httpTimedOutRequests(host).increment *>
        ZIO.fail(ActivityError(s"$method request to $host timed out", None))
    )(value => ZIO.succeed(value))(policy.timeout)
      .flatten

  private def triggerCircuitBreakerIfNeeded(enabled: Boolean) =
    if (enabled) {
      Schedule.identity[Cause[HttpFailure]].tapInput((_: Cause[HttpFailure]) => circuitBreaker.onFailure())
    } else {
      Schedule.identity[Cause[HttpFailure]]
    }

  private def observeFailure(
    host: String,
    failure: Cause[HttpFailure],
    duration: Option[Duration],
    isFinal: Boolean
  ): ZIO[Any, Nothing, Unit] =
    failure match {
      case Cause.Fail(failure @ HttpFailure.Non200Response(status), _) =>
        metrics.httpResponses(host, status).increment *>
          ZIO.foreach(duration)(duration => metrics.httpResponseTime(host, status).update(duration)) *>
          metrics.httpFailedRequests(host, Some(failure), isFinal).increment
      case Cause.Fail(failure, _) =>
        metrics.httpFailedRequests(host, Some(failure), isFinal).increment
      case Cause.Die(_, _) =>
        metrics.httpFailedRequests(host, None, isFinal).increment
      case _ =>
        ZIO.unit
    }

  private def observeSuccess(host: String, duration: Duration): ZIO[Any, Nothing, Unit] =
    metrics.httpResponses(host, Status.Ok).increment *>
      metrics.httpResponseTime(host, Status.Ok).update(duration)

  private def observeRetries(
    host: String,
    schedule: Schedule[Any, Cause[HttpFailure], Any]
  ): Schedule[Any, Cause[HttpFailure], Any] =
    schedule.onDecision {
      case (_, _, Schedule.Decision.Continue(_)) =>
        metrics.httpRetriedRequests(host).increment
      case _ =>
        ZIO.unit
    }
}

object HttpRetryLogic {
  def make(policy: HttpOperationPolicy): ZIO[Any, Nothing, HttpRetryLogic] =
    for {
      circuitBreaker <-
        policy.circuitBreakerPolicy match {
          case Some(resetPolicy) => CircuitBreaker.make(resetPolicy)
          case None              => CircuitBreaker.disabled
        }
      retryLogic <- make(policy, circuitBreaker)
    } yield retryLogic

  private[internal] def make(
    policy: HttpOperationPolicy,
    circuitBreaker: CircuitBreaker
  ): ZIO[Any, Nothing, HttpRetryLogic] =
    for {
      semaphore <- Semaphore.make(policy.maxParallelRequestCount.toLong)
    } yield new HttpRetryLogic(policy, semaphore, circuitBreaker)

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
      case RetryLimit.ElapsedTime(duration)  => Schedule.upTo(duration)
      case RetryLimit.NumberOfRetries(count) => Schedule.recurs(count)
    }

  private[internal] def optionallyJittered[Env, In, Out](jittered: Boolean, schedule: Schedule[Env, In, Out]) =
    if (jittered) schedule.jittered else schedule

}
