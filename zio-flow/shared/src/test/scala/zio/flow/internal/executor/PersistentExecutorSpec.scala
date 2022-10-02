package zio.flow.internal.executor

import zio.flow._
import zio.flow.internal._
import zio.flow.mock.MockedOperation
import zio.flow.operation.http.API
import zio.flow.{Activity, ActivityError, Operation, Remote, ZFlow}
import zio._
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.test.Assertion._
import zio.test._
import zio.{Chunk, Clock, Exit, ZNothing}

import java.time.Instant
import java.util.concurrent.TimeUnit

object PersistentExecutorSpec extends PersistentExecutorBaseSpec {

  private val testActivity: Activity[Int, Int] =
    Activity(
      "Test Activity",
      "Mock activity created for test",
      Operation.Http[Int, Int](
        "testUrlForActivity.com",
        API.get("").input[Int].output[Int]
      ),
      ZFlow.succeed(12),
      ZFlow.unit
    )

  override def flowSpec: Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore, Any] =
    suite("Operators in single run")(
      testFlow("succeed")(ZFlow.succeed(12)) { result =>
        assertTrue(result == 12)
      },
      testFlow("newVar") {
        for {
          variable         <- ZFlow.newVar("variable1", 10)
          modifiedVariable <- variable.modify(isOdd)
          v                <- modifiedVariable
        } yield v
      } { result =>
        assertTrue(result == false)

      },
      testFlow("foldM - success side") {
        ZFlow
          .succeed(15)
          .foldFlow(_ => ZFlow.unit, _ => ZFlow.unit)
      } { result =>
        assertTrue(result == unit)
      },
      testFlow("foldM - error side") {
        ZFlow
          .fail(15)
          .foldFlow(_ => ZFlow.unit, _ => ZFlow.unit)
      } { result =>
        assertTrue(result == unit)
      },
      testFlow("flatMap 1") {
        ZFlow
          .succeed(12)
          .flatMap(rA => rA + Remote(1))
      } { result =>
        assertTrue(result == 13)
      },
      testFlow("flatMap 2") {
        for {
          a <- ZFlow.succeed(12)
          b <- ZFlow.succeed(a + 10)
        } yield b
      } { result =>
        assertTrue(result == 22)
      },
      testFlow("input") {
        ZFlow.input[Int].provide(12)
      } { result =>
        assertTrue(result == 12)
      },
      testFlow("ensuring") {
        for {
          res <- ZFlow.newVar[Int]("res", 0)
          _   <- res.set(100)
          a   <- ZFlow.succeed(12).ensuring(res.set(15))
          b   <- res.get
        } yield a + b
      } { result =>
        assertTrue(result == 27)
      },
      testFlow("provide") {
        {
          for {
            a <- ZFlow.succeed(15)
            b <- ZFlow.input[Int]
          } yield a + b
        }.provide(11)
      } { result =>
        assertTrue(result == 26)
      },
      test("now") {
        TestClock.adjust(5.seconds) *> {
          val flow = ZFlow.now
          flow.evaluateTestPersistent("now").map { result =>
            assertTrue(result.getEpochSecond == 5L)
          }
        }
      },
      test("waitTill") {
        for {
          curr <- Clock.currentTime(TimeUnit.SECONDS)
          flow = for {
                   _   <- ZFlow.waitTill(Remote(Instant.ofEpochSecond(curr + 2L)))
                   now <- ZFlow.now
                 } yield now
          fiber  <- flow.evaluateTestPersistent("waitTill").fork
          _      <- TestClock.adjust(2.seconds)
          result <- fiber.join
        } yield assertTrue(result.getEpochSecond == 2L)
      },
      test("sleep") {
        for {
          curr <- Clock.currentTime(TimeUnit.SECONDS)
          flow = for {
                   _   <- ZFlow.sleep(2.seconds)
                   now <- ZFlow.now
                 } yield now
          fiber  <- flow.evaluateTestPersistent("sleep").fork
          _      <- TestClock.adjust(2.seconds)
          result <- fiber.join
        } yield assertTrue(result.getEpochSecond == 2L)
      },
      testFlow("Activity") {
        testActivity(12)
      }(
        assert = result => assertTrue(result == 12),
        mock = MockedOperation.Http[Int, Int](
          urlMatcher = equalTo("testUrlForActivity.com"),
          methodMatcher = equalTo("GET"),
          result = () => 12
        )
      ),
      testFlow("iterate") {
        ZFlow.succeed(1).iterate[Any, ZNothing, Int](_ + 1)(_ !== 10)
      } { result =>
        assertTrue(result == 10)
      } @@ TestAspect.ignore, // TODO
      testFlow("Read") {
        for {
          variable <- ZFlow.newVar[Int]("var", 0)
          a        <- variable.get
        } yield a
      } { result =>
        assertTrue(result == 0)
      },
      testFlow("Modify") {
        for {
          variable <- ZFlow.newVar[Int]("var", 0)
          a        <- variable.modify(n => (n - 1, n + 1))
          b        <- variable.get
        } yield (a, b)
      } { result =>
        val expected = (-1, 1)
        assertTrue(result == expected)
      },
      testFlowAndLogs("log") {
        ZFlow.log("first message") *> ZFlow.log("second message").as(100)
      } { (result, logs) =>
        val i1 = logs.indexOf("first message")
        val i2 = logs.indexOf("second message")
        assertTrue(
          result == 100,
          i1 >= 0,
          i2 >= 0,
          i1 < i2
        )
      },
      suite("transactions")(
        testFlow("nop transaction") {
          ZFlow.transaction { _ =>
            ZFlow.succeed(100)
          }
        } { result =>
          assertTrue(result == 100)
        },
        testFlow("setting variables in transaction") {
          for {
            var1 <- ZFlow.newVar[Int]("var1", 10)
            var2 <- ZFlow.newVar[Int]("var2", 20)
            _ <- ZFlow.transaction { _ =>
                   for {
                     _ <- var1.set(100)
                     _ <- var2.set(200)
                   } yield ()
                 }
            v1 <- var1.get
            v2 <- var2.get
          } yield (v1, v2)
        } { result =>
          assertTrue(result == ((100, 200)))
        },
        testFlow("setting variables in a forked transaction") {
          for {
            var1 <- ZFlow.newVar[Int]("var1", 10)
            fiber <- ZFlow.transaction { _ =>
                       for {
                         _ <- var1.set(100)
                       } yield ()
                     }.fork
            _  <- fiber.await
            v1 <- var1.get
          } yield v1
        } { result =>
          assertTrue(result == 100)
        },
        testFlow("can return with a variable not existing outside") {
          for {
            fiber <- ZFlow.transaction { _ =>
                       for {
                         variable <- ZFlow.newVar("inner", 123)
                         v0       <- variable.get
                       } yield v0
                     }.fork
            v1 <- fiber.await
          } yield v1
        } { result =>
          assertTrue(result == Right(123))
        },
        testFlow("conflicting change of shared variable in transaction", periodicAdjustClock = Some(500.millis)) {
          for {
            var1 <- ZFlow.newVar[Int]("var1", 10)
            var2 <- ZFlow.newVar[Int]("var2", 20)
            now  <- ZFlow.now
            fib1 <- ZFlow.transaction { _ =>
                      for {
                        _ <- ZFlow.waitTill(now.plusSeconds(1L))
                        _ <- var1.update(_ + 1)
                        _ <- ZFlow.waitTill(now.plusSeconds(1L))
                        _ <- var2.update(_ + 1)
                        _ <- ZFlow.waitTill(now.plusSeconds(1L))
                      } yield ()
                    }.fork
            fib2 <- ZFlow.transaction { _ =>
                      for {
                        _ <- ZFlow.waitTill(now.plusSeconds(1L))
                        _ <- var1.update(_ + 1)
                        _ <- ZFlow.waitTill(now.plusSeconds(1L))
                        _ <- var2.update(_ + 1)
                      } yield ()
                    }.fork

            _  <- fib1.await
            _  <- fib2.await
            v1 <- var1.get
            v2 <- var2.get
          } yield (v1, v2)
        } { result =>
          assertTrue(result == ((12, 22)))
        },
        testFlow(
          "retried transaction runs activity compensations in reverse order",
          periodicAdjustClock = Some(1.second)
        ) {

          val activity1Undo1 = Activity(
            "activity1Undo1",
            "activity1Undo1",
            Operation.Http("http://activity1/undo/1", API.get("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.unit
          )
          val activity1Undo2 = Activity(
            "activity1Undo2",
            "activity1Undo2",
            Operation.Http("http://activity1/undo/2", API.get("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.unit
          )
          val activity2Undo = Activity(
            "activity2Undo",
            "activity2Undo",
            Operation.Http("http://activity2/undo", API.get("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.unit
          )

          val activity1 = Activity(
            "activity1",
            "activity1",
            Operation.Http("http://activity1", API.get("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.input[Int].flatMap(n => activity1Undo1(n) *> activity1Undo2(n)).unit
          )
          val activity2 = Activity(
            "activity2",
            "activity2",
            Operation.Http("http://activity2", API.post("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.input[Int].flatMap(n => activity2Undo(n)).unit
          )

          for {
            var1 <- ZFlow.newVar[Int]("var1", 1)
            fib1 <- ZFlow.transaction { _ =>
                      for {
                        _  <- ZFlow.log(s"Starting transaction")
                        v1 <- var1.get
                        _  <- ZFlow.log(s"Got value of var1")
                        _  <- activity1(v1)
                        _  <- activity2(v1)
                        _  <- var1.set(v1 + 1)
                      } yield ()
                    }.fork
            now <- ZFlow.now
            _   <- ZFlow.waitTill(now.plusSeconds(2L))
            _   <- ZFlow.log(s"Modifying value var1")
            _   <- var1.set(10)
            _   <- ZFlow.log(s"Modified value var1")
            _   <- fib1.await
          } yield ()
        }(
          result => assertTrue(result == (())),
          mock =
            // First run (with input=1)
            MockedOperation.Http(
              urlMatcher = equalTo("http://activity1"),
              methodMatcher = equalTo("GET"),
              inputMatcher = equalTo(1),
              result = () => 100,
              duration = 2.seconds
            ) ++
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity2"),
                methodMatcher = equalTo("POST"),
                inputMatcher = equalTo(1),
                result = () => 200,
                duration = 2.seconds
              ) ++
              // Revert
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity2/undo"),
                methodMatcher = equalTo("GET"),
                inputMatcher = equalTo(200),
                result = () => -2
              ) ++
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity1/undo/1"),
                methodMatcher = equalTo("GET"),
                inputMatcher = equalTo(100),
                result = () => -1
              ) ++
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity1/undo/2"),
                methodMatcher = equalTo("GET"),
                inputMatcher = equalTo(100),
                result = () => -11
              ) ++
              // Second run (with input=10)
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity1"),
                methodMatcher = equalTo("GET"),
                inputMatcher = equalTo(10),
                result = () => 100,
                duration = 10.millis
              ) ++
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity2"),
                methodMatcher = equalTo("POST"),
                inputMatcher = equalTo(10),
                result = () => 200,
                duration = 10.millis
              )
        ),
        testFlowExit(
          "failed transaction runs activity compensations in reverse order",
          periodicAdjustClock = Some(1.second)
        ) {

          val activity1Undo = Activity(
            "activity1Undo",
            "activity1Undo",
            Operation.Http("http://activity1/undo", API.get("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.unit
          )
          val activity2Undo = Activity(
            "activity2Undo",
            "activity2Undo",
            Operation.Http("http://activity2/undo", API.get("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.unit
          )

          val activity1 = Activity(
            "activity1",
            "activity1",
            Operation.Http("http://activity1", API.get("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.input[Int].flatMap(n => activity1Undo(n)).unit
          )
          val activity2 = Activity(
            "activity2",
            "activity2",
            Operation.Http("http://activity2", API.post("").input[Int].output[Int]),
            ZFlow.succeed(0),
            ZFlow.input[Int].flatMap(n => activity2Undo(n)).unit
          )

          for {
            _ <- ZFlow.transaction { _ =>
                   for {
                     _ <- activity1(1)
                     _ <- activity2(1)
                     _ <- ZFlow.fail(ActivityError("simulated failure", None))
                   } yield ()
                 }
          } yield ()
        }(
          result =>
            assert(result)(fails(equalTo(ActivityError("simulated failure", None)))) &&
              assert(result.causeOption.flatMap(_.dieOption))(isNone),
          mock =
            // First run
            MockedOperation.Http(
              urlMatcher = equalTo("http://activity1"),
              methodMatcher = equalTo("GET"),
              inputMatcher = equalTo(1),
              result = () => 100
            ) ++
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity2"),
                methodMatcher = equalTo("POST"),
                inputMatcher = equalTo(1),
                result = () => 200
              ) ++
              // Revert
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity2/undo"),
                methodMatcher = equalTo("GET"),
                inputMatcher = equalTo(200),
                result = () => -2
              ) ++
              MockedOperation.Http(
                urlMatcher = equalTo("http://activity1/undo"),
                methodMatcher = equalTo("GET"),
                inputMatcher = equalTo(100),
                result = () => -1
              )
        ),
        testFlowAndLogs("retry until", periodicAdjustClock = Some(200.millis)) {
          for {
            variable <- ZFlow.newVar("var", 0)
            fiber <- ZFlow.transaction { tx =>
                       for {
                         value <- variable.get
                         _     <- ZFlow.log("TX")
                         _     <- tx.retryUntil(value === 1)
                       } yield value
                     }.fork
            _      <- ZFlow.sleep(1.second)
            _      <- variable.set(1)
            _      <- ZFlow.log("Waiting for the transaction fiber to stop")
            result <- fiber.await
          } yield result
        } { (result, logs) =>
          assertTrue(
            result == Right(1),
            logs.filter(_ == "TX").size == 2
          )
        },
        testFlowAndLogs("retry until multiple times", periodicAdjustClock = Some(200.millis)) {
          for {
            variable <- ZFlow.newVar("var", 0)
            fiber <- ZFlow.transaction { tx =>
                       for {
                         value <- variable.get
                         _     <- ZFlow.log("TX")
                         _     <- tx.retryUntil(value === 2)
                       } yield value
                     }.fork
            _      <- ZFlow.sleep(1.second)
            _      <- variable.set(1)
            _      <- ZFlow.sleep(1.second)
            _      <- variable.set(2)
            _      <- ZFlow.log("Waiting for the transaction fiber to stop")
            result <- fiber.await
          } yield result
        } { (result, logs) =>
          assertTrue(
            result == Right(2),
            logs.filter(_ == "TX").size == 3
          )
        },
        testFlowAndLogs("orTry, second succeeds", periodicAdjustClock = Some(200.millis)) {
          for {
            variable <- ZFlow.newVar("var", 0)
            fiber <- ZFlow.transaction { tx =>
                       for {
                         value <- variable.get
                         _     <- ZFlow.log("TX1")
                         _ <- tx.retryUntil(value === 1).orTry {
                                ZFlow.log("TX2")
                              }
                       } yield 1
                     }.fork
            _      <- ZFlow.log("Waiting for the transaction fiber to stop")
            result <- fiber.await
          } yield result
        } { (result, logs) =>
          assertTrue(
            result == Right(1),
            logs.filter(_.startsWith("TX")) == Chunk("TX1", "TX2")
          )
        }
      ),
      testFlow("unwrap") {
        val flow = for {
          wrapped   <- ZFlow.input[ZFlow[Any, ZNothing, Int]]
          unwrapped <- ZFlow.unwrap(wrapped)
          result    <- unwrapped
        } yield result
        flow.provide(ZFlow.succeed(100))
      } { result =>
        assertTrue(result == 100)
      },
      testFlow("unwrap twice") {
        for {
          wrapped <- ZFlow.succeed(ZFlow.succeed(ZFlow.succeed(10)))
          first   <- ZFlow.unwrap(wrapped)
          second  <- ZFlow.unwrap(first)
          result  <- second
        } yield result
      } { result =>
        assertTrue(result == 10)
      },
      testFlow("nested unwrap") {
        for {
          wrapped <- ZFlow.succeed(ZFlow.unwrap(ZFlow.succeed(ZFlow.succeed(10))))
          first   <- ZFlow.unwrap(wrapped)
          second  <- ZFlow.unwrap(first)
          result  <- second
        } yield result
      } { result =>
        assertTrue(result == 10)
      },
      test("fork/await") {
        for {
          curr <- Clock.currentTime(TimeUnit.SECONDS)
          flow = for {
                   flow1 <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 2L)).as(1).fork
                   flow2 <- ZFlow.waitTill(Remote.ofEpochSecond(curr + 3L)).as(2).fork
                   r1    <- flow1.await
                   r2    <- flow2.await
                   _     <- ZFlow.log(r1.toString)
                 } yield (r1.toOption, r2.toOption)
          fiber   <- flow.evaluateTestPersistent("fork").fork
          _       <- TestClock.adjust(3.seconds)
          result  <- fiber.join
          expected = (Some(1), Some(2))
        } yield assertTrue(result == expected)
      },
      testFlowAndLogs("fork/interrupt", periodicAdjustClock = Some(1.seconds)) {
        for {
          now   <- ZFlow.now
          flow1 <- (ZFlow.waitTill(now.plusSeconds(2L)) *> ZFlow.log("first")).fork
          flow2 <- (ZFlow.waitTill(now.plusSeconds(3L)) *> ZFlow.log("second")).fork
          flow3 <- (ZFlow.waitTill(now.plusSeconds(5L)) *> ZFlow.log("third")).fork
          _     <- flow1.await
          _     <- flow2.interrupt
          _     <- flow3.await
        } yield 111
      } { (result, logs) =>
        assertTrue(
          result == 111,
          logs.contains("first"),
          logs.contains("third"),
          !logs.contains("second")
        )
      },
      test("timeout works") {
        for {
          curr <- Clock.currentTime(TimeUnit.SECONDS)
          flow = ZFlow
                   .waitTill(Remote.ofEpochSecond(curr + 2L))
                   .as(1)
                   .timeout(Duration.ofSeconds(1L))
          fiber  <- flow.evaluateTestPersistent("timeout").fork
          _      <- TestClock.adjust(3.seconds)
          result <- fiber.join
        } yield assertTrue(result == None)
      },
      testFlow("timeout interrupts", periodicAdjustClock = Some(1.seconds)) {
        for {
          now <- ZFlow.now
          v   <- ZFlow.newVar[Boolean]("result", false)
          _ <- (for {
                 _ <- ZFlow.waitTill(now.plusSeconds(2L))
                 _ <- v.set(true)
               } yield ()).timeout(Duration.ofSeconds(1L))
          _ <- ZFlow.waitTill(now.plusSeconds(3L))
          r <- v.get
        } yield r
      } { result =>
        assertTrue(result == false)
      },
      testFlowExit[String, Nothing]("die") {
        ZFlow.fail("test").orDie
      } { (result: Exit[String, Nothing]) =>
        assert(result)(dies(hasMessage(equalTo("Could not evaluate ZFlow"))))
      },
      testFlow("cons") {
        ZFlow(Remote(1))
          .map(Remote.Cons(Remote(List.empty[Int]), _))
      } { result =>
        assertTrue(result == List(1))
      },
      testFlow("zflow fold") {
        ZFlow
          .succeed(1)
          .foldFlow(
            (_: Remote[ZNothing]) => ZFlow.succeed("failed"),
            (_: Remote[Int]) => ZFlow.succeed("succeeded")
          )
      } { res =>
        assertTrue(res == "succeeded")
      },
      testFlow("list fold") {
        ZFlow.succeed(
          Remote(List(1, 2, 3))
            .foldLeft(Remote(0))(_ + _)
        )
      } { res =>
        assertTrue(res == 6)
      },
      testFlow("list fold zflows, ignore accumulator") {
        ZFlow.unwrap {
          Remote(List(1, 2, 3))
            .foldLeft(ZFlow.succeed(0)) { case (_, n) =>
              ZFlow.succeed(n)
            }
        }
      } { res =>
        assertTrue(res == 3)
      },
      testFlow("list fold zflows, ignore elem") {
        ZFlow.unwrap {
          Remote(List(1, 2, 3))
            .foldLeft(ZFlow.succeed(0)) { case (acc, _) =>
              acc
            }
        }
      } { res =>
        assertTrue(res == 0)
      },
      testFlow("unwrap list fold zflows") {
        val foldedFlow = Remote(List(1, 2))
          .foldLeft(ZFlow.succeed(0)) { case (flow, n) =>
            flow.flatMap { prevFlow =>
              ZFlow.unwrap(prevFlow).map(_ + n)
            }
          }
        ZFlow.unwrap {
          foldedFlow
        }
      } { res =>
        assertTrue(res == 3)
      },
      testFlow("unwrap list manually fold zflows") {
        ZFlow.unwrap {
          ZFlow.unwrap(Remote(ZFlow.unwrap(Remote(ZFlow.succeed(0))).map(_ + 1))).map(_ + 2)
        }
      } { res =>
        assertTrue(res == 3)
      },
      testFlow("foreach") {
        ZFlow.foreach(Remote(List.range(1, 10)))(ZFlow(_))
      } { res =>
        assertTrue(res == List.range(1, 10))
      },
      testFlow("foreachPar") {
        ZFlow.foreachPar(Remote(List.range(1, 10)))(ZFlow(_))
      } { res =>
        assertTrue(res == List.range(1, 10))
      },
      testFlow("unwrap remote flows in parallel") {
        val flows: List[ZFlow[Any, ZNothing, Int]] =
          List.range(1, 10).map(ZFlow(_))

        val collected: ZFlow[Any, ActivityError, List[Int]] =
          ZFlow.foreachPar(Remote(flows))(ZFlow.unwrap[Any, ZNothing, Int])

        collected
      } { collected =>
        assertTrue(collected == List.range(1, 10))
      },
      testFlow("remote recursion") {
        ZFlow.unwrap {
          Remote.recurseSimple[ZFlow[Any, ZNothing, Int]](ZFlow.succeed(0)) { case (getValue, rec) =>
            (for {
              value <- ZFlow.unwrap(getValue)
              _     <- ZFlow.log("recursion step")
              result <- ZFlow.ifThenElse(value === 10)(
                          ifTrue = ZFlow.succeed(value),
                          ifFalse = ZFlow.unwrap(rec(ZFlow.succeed(value + 1)))
                        )
            } yield result).toRemote
          }
        }
      } { res =>
        assertTrue(res == 10)
      },
      testFlow("flow recursion") {
        ZFlow.recurseSimple[Any, ZNothing, Int](0) { case (value, rec) =>
          ZFlow.log("recursion step") *>
            ZFlow.ifThenElse(value === 10)(
              ifTrue = ZFlow.succeed(value),
              ifFalse = rec(value + 1)
            )
        }
      } { res =>
        assertTrue(res == 10)
      },
      suite("waitUntil")(
        testFlow("waitUntil with timeout", periodicAdjustClock = Some(500.millis)) {
          for {
            v1         <- ZFlow.newVar[Int]("v1", 0)
            waitResult <- v1.waitUntil(_ === 1).timeout(1.second)
            _          <- v1.set(2)
            result     <- v1.get
          } yield (result, waitResult)
        } { case (r, wr) =>
          assertTrue(
            r == 2,
            wr.isEmpty
          )
        },
        testFlow("waitUntil proceeds on change", periodicAdjustClock = Some(500.millis)) {
          for {
            v1    <- ZFlow.newVar[Int]("v1", 0)
            fiber <- (v1.waitUntil(_ === 2) *> ZFlow.log("GOT THE VALUE") *> v1.get).fork
            _     <- ZFlow.sleep(1.second)
            _     <- ZFlow.log("Setting v1 to 1")
            _     <- v1.set(1)
            fr1   <- fiber.await.timeout(10.millis)
            _     <- ZFlow.sleep(1.second)
            _     <- ZFlow.log("Setting v1 to 2")
            _     <- v1.set(2)
            fr2   <- fiber.await.timeout(1.seconds)
          } yield (fr1, fr2)
        } { case (fr1, fr2) =>
          assertTrue(
            fr1.isEmpty,
            fr2.get == Right(2)
          )
        }
      ),
      testFlowAndLogs("repeatWhile", periodicAdjustClock = Some(500.millis)) {
        for {
          boolVar <- ZFlow.newVar("bool", true)
          fiber <- ZFlow.repeatWhile {
                     ZFlow.sleep(1.seconds) *> ZFlow.log("!!LOOP!!") *> boolVar.get
                   }.fork
          _      <- ZFlow.sleep(3.seconds)
          _      <- boolVar.set(false)
          result <- fiber.await
        } yield result
      } { case (result, logs) =>
        assertTrue(
          result == Right(false),
          logs.filter(_.contains("!!LOOP!!")).length >= 3
        )
      },
      testFlowAndLogs("repeatUntil", periodicAdjustClock = Some(500.millis)) {
        for {
          boolVar <- ZFlow.newVar("bool", false)
          fiber <- ZFlow.repeatUntil {
                     ZFlow.sleep(1.seconds) *> ZFlow.log("!!LOOP!!") *> boolVar.get
                   }.fork
          _      <- ZFlow.sleep(3.seconds)
          _      <- boolVar.set(true)
          result <- fiber.await
        } yield result
      } { case (result, logs) =>
        assertTrue(
          result == Right(true),
          logs.filter(_.contains("!!LOOP!!")).length >= 3
        )
      },
      testFlow("catchAll") {
        ZFlow.fail(10).catchAll { (error: Remote[Int]) =>
          ZFlow.succeed(error + 1)
        }
      } { result =>
        assertTrue(result == 11)
      },
      testFlow("orElse") {
        ZFlow.fail(10).orElse(ZFlow.succeed(11))
      } { result =>
        assertTrue(result == 11)
      },
      testFlow("orElseEither - left") {
        ZFlow.succeed(10).orElseEither(ZFlow.succeed("hello"))
      } { result =>
        assertTrue(result == Left(10))
      },
      testFlow("orElseEither - right") {
        ZFlow.fail(10).orElseEither(ZFlow.succeed("hello"))
      } { result =>
        assertTrue(result == Right("hello"))
      },
      testFlow("zip") {
        ZFlow.succeed(1).zip(ZFlow.succeed(2))
      } { result =>
        val expected = (1, 2)
        assertTrue(result == expected)
      },
      testFlow("zipLeft") {
        ZFlow.succeed(1).zipLeft(ZFlow.succeed(2))
      } { result =>
        assertTrue(result == 1)
      },
      testFlow("zipRight") {
        ZFlow.succeed(1).zipRight(ZFlow.succeed(2))
      } { result =>
        assertTrue(result == 2)
      },
      testFlow("unwrap remote") {
        ZFlow.unwrapRemote(Remote(Remote("hello"))(Remote.schema[String]))
      } { result =>
        assertTrue(result == "hello")
      },
      testFlow("fromEither - right") {
        ZFlow.fromEither(Remote.right[Int, String]("hello"))
      } { result =>
        assertTrue(result == "hello")
      },
      testFlowExit("fromEither - left") {
        ZFlow.fromEither(Remote.left[Int, String](11))
      } { result =>
        assert(result)(fails(equalTo(11)))
      }
    )

  private def isOdd(a: Remote[Int]): (Remote[Boolean], Remote[Int]) =
    (((a % 2) === 1), a)
}
