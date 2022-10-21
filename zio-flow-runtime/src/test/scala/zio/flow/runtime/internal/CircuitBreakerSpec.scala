package zio.flow.runtime.internal

import zio.flow.runtime.operation.http.{Repetition, RetryLimit, RetryPolicy}
import zio.test.{Spec, TestClock, TestEnvironment, ZIOSpecDefault, assertTrue}
import zio.{Scope, ZIO, durationInt}

object CircuitBreakerSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("CircuitBreaker")(
      test("stays open if there is no failure") {
        for {
          cb <- CircuitBreaker.make(
                  RetryPolicy(
                    failAfter = RetryLimit.NumberOfRetries(3),
                    repeatWith = Repetition.Fixed(1.second),
                    jitter = false
                  )
                )
          states <- ZIO.foreach((1 to 10).toList)(_ => cb.onSuccess() *> cb.isOpen)
        } yield assertTrue(states.forall(x => !x))
      },
      test("single failure opens the breaker and it closes after the expected delay") {
        for {
          cb <- CircuitBreaker.make(
                  RetryPolicy(
                    failAfter = RetryLimit.NumberOfRetries(3),
                    repeatWith = Repetition.Exponential(1.second, 2.0, 10.seconds),
                    jitter = false
                  )
                )
          states <- ZIO.foreach((1 to 10).toList) { idx =>
                      (if (idx == 3) cb.onFailure() else cb.onSuccess()) *>
                        cb.isOpen <*
                        TestClock.adjust(450.millis)
                    }
        } yield assertTrue(states == List(false, false, true, true, true, false, false, false, false, false))
      },
      test("closes again and schedule continues if it fails immediately") {
        for {
          cb <- CircuitBreaker.make(
                  RetryPolicy(
                    failAfter = RetryLimit.NumberOfRetries(3),
                    repeatWith = Repetition.Exponential(1.second, 2.0, 10.seconds),
                    jitter = false
                  )
                )
          states <- ZIO.foreach((1 to 12).toList) { idx =>
                      (if (idx == 3 || idx == 6) cb.onFailure() else cb.onSuccess()) *>
                        cb.isOpen <*
                        TestClock.adjust(450.millis)
                    }
        } yield assertTrue(states == List(false, false, true, true, true, true, true, true, true, true, false, false))
      },
      test("restarts close schedule when after next failure after it completed") {
        for {
          cb <- CircuitBreaker.make(
                  RetryPolicy(
                    failAfter = RetryLimit.NumberOfRetries(2),
                    repeatWith = Repetition.Exponential(1.second, 2.0, 2.seconds),
                    jitter = false
                  )
                )
          states <- ZIO.foreach((1 to 12).toList) { idx =>
                      (if (idx == 1 || idx == 4 || idx == 9) cb.onFailure() else cb.onSuccess()) *>
                        cb.isOpen <*
                        TestClock.adjust(450.millis)
                    }
        } yield assertTrue(
          states == List(
            true, true, true,             // first it stays close for 1 seconds
            true, true, true, true, true, // next for 2 seconds
            true, true, true,             // finally for 1 second again
            false                         // and then it opens
          )
        )
      }
    )
}
