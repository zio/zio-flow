/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.runtime.internal

import zhttp.http.Status
import zio._
import zio.flow.ActivityError
import zio.flow.operation.http.{HttpFailure, HttpMethod}
import zio.flow.runtime.operation.http.{
  HttpOperationPolicy,
  HttpRetryCondition,
  HttpRetryPolicy,
  Repetition,
  RetryLimit,
  RetryPolicy
}
import zio.stm.TRef
import zio.test._
import zio.test.Assertion._

object RetryLogicSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RetryLogic")(
      suite("timeout")(
        test("action can finish before the timeout") {
          for {
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List.empty,
                              circuitBreakerPolicy = None,
                              timeout = 2.seconds
                            )
                          )
            fib    <- retryLogic(HttpMethod.GET, "host")(ZIO.sleep(1.second).as(true)).forkScoped
            _      <- TestClock.adjust(5.seconds)
            result <- fib.join.exit
          } yield assert(result)(succeeds(equalTo(true)))
        },
        test("timeout is applied") {
          for {
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List.empty,
                              circuitBreakerPolicy = None,
                              timeout = 2.seconds
                            )
                          )
            fib    <- retryLogic(HttpMethod.GET, "host")(ZIO.never).forkScoped
            _      <- TestClock.adjust(5.seconds)
            result <- fib.join.exit
          } yield assert(result)(fails(equalTo(ActivityError("GET request to host timed out", None))))
        }
      ),
      suite("conditions")(
        test("always") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.Always,
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(2),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.CircuitBreakerOpen)
                         case 2 => ZIO.succeed(true)
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(1.second)
            result <- fib.join.exit
            count  <- tryCount.get
          } yield assert(result)(succeeds(equalTo(true))) &&
            assertTrue(count == 2)
        },
        test("open circuit breaker") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.OpenCircuitBreaker,
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(5),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.CircuitBreakerOpen)
                         case 2 => ZIO.fail(HttpFailure.CircuitBreakerOpen)
                         case 3 => ZIO.fail(HttpFailure.Non200Response(Status.InternalServerError))
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(3.second)
            result <- fib.join.exit
            count  <- tryCount.get
          } yield assert(result)(
            fails(equalTo(ActivityError("GET request to host responded with 500 InternalServerError", None)))
          ) &&
            assertTrue(count == 3)
        },
        test("for specific status") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.ForSpecificStatus(Status.TooManyRequests.code),
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(5),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.Non200Response(Status.TooManyRequests))
                         case 2 => ZIO.fail(HttpFailure.Non200Response(Status.TooManyRequests))
                         case 3 => ZIO.fail(HttpFailure.Non200Response(Status.InternalServerError))
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(3.second)
            result <- fib.join.exit
            count  <- tryCount.get
          } yield assert(result)(
            fails(equalTo(ActivityError("GET request to host responded with 500 InternalServerError", None)))
          ) &&
            assertTrue(count == 3)
        },
        test("for 4xx status") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.For4xx,
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(5),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.Non200Response(Status.TooManyRequests))
                         case 2 => ZIO.fail(HttpFailure.Non200Response(Status.TooManyRequests))
                         case 3 => ZIO.fail(HttpFailure.Non200Response(Status.InternalServerError))
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(3.second)
            result <- fib.join.exit
            count  <- tryCount.get
          } yield assert(result)(
            fails(equalTo(ActivityError("GET request to host responded with 500 InternalServerError", None)))
          ) &&
            assertTrue(count == 3)
        },
        test("for 5xx status") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.For5xx,
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(5),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.Non200Response(Status.ServiceUnavailable))
                         case 2 => ZIO.fail(HttpFailure.Non200Response(Status.ServiceUnavailable))
                         case 3 => ZIO.fail(HttpFailure.Non200Response(Status.BadRequest))
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(3.second)
            result <- fib.join.exit
            count  <- tryCount.get
          } yield assert(result)(
            fails(equalTo(ActivityError("GET request to host responded with 400 BadRequest", None)))
          ) &&
            assertTrue(count == 3)
        },
        test("or") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.Or(
                                    HttpRetryCondition.ForSpecificStatus(429),
                                    HttpRetryCondition.ForSpecificStatus(500)
                                  ),
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(5),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.Non200Response(Status.TooManyRequests))
                         case 2 => ZIO.fail(HttpFailure.Non200Response(Status.InternalServerError))
                         case 3 => ZIO.fail(HttpFailure.Non200Response(Status.BadRequest))
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(3.second)
            result <- fib.join.exit
            count  <- tryCount.get
          } yield assert(result)(
            fails(equalTo(ActivityError("GET request to host responded with 400 BadRequest", None)))
          ) &&
            assertTrue(count == 3)
        }
      ),
      suite("limits")(
        test("number of retries") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.OpenCircuitBreaker,
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(3),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *> ZIO.fail(HttpFailure.CircuitBreakerOpen).sandbox
                   ).forkScoped
            _      <- TestClock.adjust(10.seconds)
            result <- fib.join.exit
            count  <- tryCount.get
          } yield assert(result)(
            fails(equalTo(ActivityError("GET request to host was canceled because circuit breaker is open", None)))
          ) &&
            assertTrue(count == 4)
        },
        test("elapsed time") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.OpenCircuitBreaker,
                                  RetryPolicy(
                                    RetryLimit.ElapsedTime(5.seconds),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *> ZIO.fail(HttpFailure.CircuitBreakerOpen).sandbox
                   ).forkScoped
            _      <- TestClock.adjust(10.seconds)
            result <- fib.join.exit
            count  <- tryCount.get
          } yield assert(result)(
            fails(equalTo(ActivityError("GET request to host was canceled because circuit breaker is open", None)))
          ) &&
            assertTrue(count == 6)
        }
      ),
      suite("separate retry policy per condition")(
        test("both gets used") {
          for {
            tryCount <- Ref.make(0)
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.ForSpecificStatus(500),
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(5),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                ),
                                HttpRetryPolicy(
                                  HttpRetryCondition.ForSpecificStatus(429),
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(5),
                                    Repetition.Fixed(5.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.Non200Response(Status.TooManyRequests))
                         case 2 => ZIO.fail(HttpFailure.Non200Response(Status.InternalServerError))
                         case 3 => ZIO.fail(HttpFailure.Non200Response(Status.BadRequest))
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(3.second)
            count1 <- tryCount.get
            _      <- TestClock.adjust(7.second)
            count2 <- tryCount.get
            result <- fib.join.exit
          } yield assert(result)(
            fails(equalTo(ActivityError("GET request to host responded with 400 BadRequest", None)))
          ) &&
            assertTrue(count1 == 1, count2 == 3)
        }
      ),
      suite("max parallel requests")(
        test("enforced") {
          for {
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 2,
                              hostOverride = None,
                              retryPolicies = List.empty,
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            )
                          )
            current <- TRef.make(0).commit
            max     <- TRef.make(0).commit
            results <- ZIO.foreachPar((1 to 20).toSet) { idx =>
                         retryLogic(HttpMethod.GET, "host") {
                           current.get.flatMap { c =>
                             val c2 = c + 1
                             current.set(c2) *> max.update(_.max(c2))
                           }.commit.as(idx).ensuring(current.update(_ - 1).commit)
                         }
                       }
            maxResult <- max.get.commit
          } yield assertTrue(results == (1 to 20).toSet, maxResult <= 2)
        }
      ),
      suite("connects to circuit breaker")(
        test("with disabled circuit breaker it does not report failures") {
          for {
            tryCount     <- Ref.make(0)
            successCount <- Ref.make(0)
            failureCount <- Ref.make(0)
            mockedCircuitBreaker = new CircuitBreaker {
                                     override def isOpen: ZIO[Any, Nothing, Boolean]   = ZIO.succeed(false)
                                     override def onSuccess(): ZIO[Any, Nothing, Unit] = successCount.update(_ + 1)
                                     override def onFailure(): ZIO[Any, Nothing, Unit] = failureCount.update(_ + 1)
                                   }
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.Always,
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(2),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = false
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            ),
                            mockedCircuitBreaker
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.CircuitBreakerOpen)
                         case 2 => ZIO.succeed(true)
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(1.second)
            result <- fib.join.exit
            count  <- tryCount.get
            scount <- successCount.get
            fcount <- failureCount.get
          } yield assert(result)(succeeds(equalTo(true))) &&
            assertTrue(count == 2, scount == 1, fcount == 0)
        },
        test("with enabled circuit breaker it reports success and failure") {
          for {
            tryCount     <- Ref.make(0)
            successCount <- Ref.make(0)
            failureCount <- Ref.make(0)
            mockedCircuitBreaker = new CircuitBreaker {
                                     override def isOpen: ZIO[Any, Nothing, Boolean] = ZIO.succeed(false)

                                     override def onSuccess(): ZIO[Any, Nothing, Unit] = successCount.update(_ + 1)

                                     override def onFailure(): ZIO[Any, Nothing, Unit] = failureCount.update(_ + 1)
                                   }
            retryLogic <- HttpRetryLogic.make(
                            HttpOperationPolicy(
                              maxParallelRequestCount = 10,
                              hostOverride = None,
                              retryPolicies = List(
                                HttpRetryPolicy(
                                  HttpRetryCondition.Always,
                                  RetryPolicy(
                                    RetryLimit.NumberOfRetries(3),
                                    Repetition.Fixed(1.second),
                                    jitter = false
                                  ),
                                  breakCircuit = true
                                )
                              ),
                              circuitBreakerPolicy = None,
                              timeout = Duration.Infinity
                            ),
                            mockedCircuitBreaker
                          )
            fib <- retryLogic(HttpMethod.GET, "host")(
                     tryCount.update(_ + 1) *>
                       tryCount.get.flatMap {
                         case 1 => ZIO.fail(HttpFailure.Non200Response(Status.InternalServerError))
                         case 2 => ZIO.fail(HttpFailure.Non200Response(Status.InternalServerError))
                         case 3 => ZIO.succeed(true)
                       }.sandbox
                   ).forkScoped
            _      <- TestClock.adjust(5.seconds)
            result <- fib.join.exit
            count  <- tryCount.get
            scount <- successCount.get
            fcount <- failureCount.get
          } yield assert(result)(succeeds(equalTo(true))) &&
            assertTrue(count == 3, scount == 1, fcount == 2)
        }
      )
    )
}
