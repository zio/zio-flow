package zio.flow

import zio.ZIO
import zio.clock.Clock
import zio.console.Console
import zio.duration.durationInt
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow.utils.ZFlowAssertionSyntax.Mocks.mockActivity
import zio.test.Assertion.equalTo
import zio.test._
import zio.test.environment.{TestClock, TestConsole}

object ExampleSpec extends DefaultRunnableSpec {

  def setBoolVarAfterSleep(remoteBoolVar: RemoteVariable[Boolean], sleepDuration: Long, value : Boolean): ZFlow[Any, Nothing, Unit] = for {
    _ <- ZFlow.sleep(Remote.ofSeconds(sleepDuration))
    _ <- remoteBoolVar.set(value)
  } yield ()

  def waitUntilOnBoolVarZFlow(remoteBoolVar: RemoteVariable[Boolean], timeoutDuration: Long, sleepDuration: Long): ZFlow[Any, Nothing, Boolean] = for {
    _ <- setBoolVarAfterSleep(remoteBoolVar, sleepDuration, true).fork
    _ <- remoteBoolVar.waitUntil(_ === true).timeout(Remote.ofSeconds(timeoutDuration))
    bool <- remoteBoolVar.get
  } yield bool

  val suite1 =
    suite("Test `waitUnit` with `timeout`")(testM("`timeout` duration met") {
      val evaluated = (for {
        remoteBoolVar <- ZFlow.newVar("boolVariable", false)
        _ <- waitUntilOnBoolVarZFlow(remoteBoolVar, 2L, 5L)
        bool <- remoteBoolVar.get
      } yield bool).evaluateTestInMem

      val result = for {
        f <- evaluated.fork
        _ <- TestClock.adjust(5.seconds)
        r <- f.join
      } yield r

      assertM(result)(equalTo(false))
    },
      testM("`waitUntil` proceeds on variable change") {
        val evaluated = (for {
          remoteBoolVar <- ZFlow.newVar("boolVariable", false)
          _ <- waitUntilOnBoolVarZFlow(remoteBoolVar, 5L, 2L)
          bool <- remoteBoolVar.get
        } yield bool).evaluateTestInMem

        val result = for {
          f <- evaluated.fork
          _ <- TestClock.adjust(5.seconds)
          r <- f.join
        } yield r

        assertM(result)(equalTo(true))
      })

  val suite2 = suite("Test `waitUnit` with `timeout` - uses live clock")(testM("`timeout` duration met") {
    val evaluated: ZIO[Clock with Console, Nothing, Boolean] = (for {
      remoteBoolVar <- ZFlow.newVar("boolVariable", false)
      _ <- waitUntilOnBoolVarZFlow(remoteBoolVar, 1L, 100L)
      bool <- remoteBoolVar.get
    } yield bool).evaluateLiveInMem

    assertM(evaluated)(equalTo(false))
  },
    testM("waitUntil proceeds on variable change") {
      val evaluated: ZIO[Clock with Console, Nothing, Boolean] = (for {
        remoteBoolVar <- ZFlow.newVar("boolVariable", false)
        //TODO : This waits for timeoutDuration time
        _ <- waitUntilOnBoolVarZFlow(remoteBoolVar, 2L, 1L)
        bool <- remoteBoolVar.get
      } yield bool).evaluateLiveInMem

      assertM(evaluated)(equalTo(true))
    })

  val suite3 = suite("Test ZFlow.when")(testM("With RunActivity") {
    val whenZflow: ZFlow[Any, ActivityError, Unit] = for {
      remoteBool <- Remote(true)
      _ <- ZFlow.when(remoteBool)(ZFlow.RunActivity(12, mockActivity))
    } yield ()

    val result = for {
      _ <- whenZflow.evaluateTestInMem
      output <- TestConsole.output
    } yield output

    assertM(result)(equalTo(Vector("Activity processing\n")))
  })

  val suite5 = suite("Test Iterate")(testM("Iterate a fixed number of times") {
    val iterateZflow: ZFlow[Any, ActivityError, Int] = ZFlow.Iterate(ZFlow.succeed(12), (remoteInt: Remote[Int]) => {
      for {
        rInt <- remoteInt + 1
        _ <- ZFlow.RunActivity(12, mockActivity)
      } yield rInt
    }, (r: Remote[Int]) => r !== 15)

    val result = for {
      _ <- iterateZflow.evaluateTestInMem
      output <- TestConsole.output
    } yield output

    assertM(result)(equalTo(Vector("Activity processing\n", "Activity processing\n", "Activity processing\n")))
  },
    testM("Iterate on combination of `timeout` and `waitUntil`") {
      val boolVarZFlow: ZFlow[Any, Nothing, Variable[Boolean]] = ZFlow.newVar("boolVarForIterate", true)

      def iterateZflow(boolVar: RemoteVariable[Boolean]): ZFlow[Any, ActivityError, Boolean] = ZFlow.Iterate(ZFlow(true), (_: Remote[Boolean]) => {
        for {
          bool <- boolVar
          _ <- setBoolVarAfterSleep(bool, 5, false).fork
          _ <- bool.waitUntil(_ === false).timeout(Remote.ofSeconds(1L))
          loop <- bool.get
          _ <- ZFlow.RunActivity(12, mockActivity)
        } yield loop
      }, (b: Remote[Boolean]) => b)

      val evaluated: ZIO[Clock with Console, ActivityError, Boolean] = (for {
        b <- boolVarZFlow
        evaluated <- iterateZflow(b)
      } yield evaluated).evaluateTestInMem

      val result = for {
        f <- evaluated.fork
        _ <- TestClock.adjust(10.seconds)
        r <- f.join
      } yield r

      assertM(result)(equalTo(false))
    })

  val suite4 = suite("More test on waitUntil")(testM("waitUntil and do-while") {
    def doWhileOnWaitUntil(boolVar: RemoteVariable[Boolean]): ZFlow[Any, ActivityError, Any] = ZFlow.doWhile({
      for {
        _ <- ZFlow.log("Inside do-while")
        option <- boolVar.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
        loop <- option.isNone.toFlow
        _ <- ZFlow.when(loop)(ZFlow.RunActivity(12, mockActivity))
      } yield loop
    })

    def doWhileOnWaitUntil1(boolVar: RemoteVariable[Boolean]): ZFlow[Any, ActivityError, Boolean] = ZFlow.Iterate(ZFlow(true), (_: Remote[Boolean]) => {
      for {
        _ <- ZFlow.log("Inside do-while")
        _ <- boolVar.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
        loop <- boolVar.get
        _ <- ZFlow.log("loop is " + loop.toString)
        _ <- ZFlow.when(loop)(ZFlow.RunActivity(12, mockActivity))
      } yield loop
    }, (b: Remote[Boolean]) => b)

    val evaluated: ZIO[Clock with Console, ActivityError, Any] = (for {
      remoteBoolVar <- ZFlow.newVar("boolVar", false)
      _ <- setBoolVarAfterSleep(remoteBoolVar, 5L, true).fork
      b <- doWhileOnWaitUntil1(remoteBoolVar)
    } yield b).evaluateTestInMem

    val result: ZIO[TestClock with Clock with Console, ActivityError, Unit] = for {
      f <- evaluated.fork
      _ <- TestClock.adjust(10.seconds)
      _ <- f.join
    } yield ()

    assertM(result)(equalTo(()))
  })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("Testing interactions between ZFlows")(suite1, suite2, suite3, suite4, suite5)
}
