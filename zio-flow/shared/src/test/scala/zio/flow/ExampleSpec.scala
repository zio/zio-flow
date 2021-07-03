package zio.flow

import zio.ZIO
import zio.clock.Clock
import zio.console.Console
import zio.duration.durationInt
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow.utils.ZFlowAssertionSyntax.Mocks.mockActivity
import zio.schema.Schema
import zio.test.Assertion.equalTo
import zio.test._
import zio.test.environment.{TestClock, TestConsole}

import java.net.URI

object ExampleSpec extends DefaultRunnableSpec {

  val createBoolVarZFlow: ZFlow[Any, Nothing, Variable[Boolean]] = ZFlow.newVar("boolVariable", false)

  def setBoolVarToTrueAfterSleep(remoteBoolVar: RemoteVariable[Boolean], sleepDuration: Long): ZFlow[Any, Nothing, Unit] = for {
    _ <- ZFlow.sleep(Remote.ofSeconds(sleepDuration))
    _ <- remoteBoolVar.set(true)
  } yield ()

  def waitUntilOnBoolVarZFlow(remoteBoolVar: RemoteVariable[Boolean], timeoutDuration: Long, sleepDuration: Long): ZFlow[Any, Nothing, Boolean] = for {
    _ <- setBoolVarToTrueAfterSleep(remoteBoolVar, sleepDuration).fork
    _ <- remoteBoolVar.waitUntil(_ === true).timeout(Remote.ofSeconds(timeoutDuration))
    bool <- remoteBoolVar.get
  } yield bool


  val suite1 =
    suite("Tests on RemoteVariable")(testM("waitUntil times out") {
      val evaluated = (for {
        remoteBoolVar <- createBoolVarZFlow
        _ <- waitUntilOnBoolVarZFlow(remoteBoolVar, 2L, 5L)
        bool <- remoteBoolVar.get
      } yield bool).evaluateTestInMem

      val result = for {
        f <- evaluated.fork
        _ <- TestClock.adjust(10.seconds)
        r <- f.join
      } yield r

      assertM(result)(equalTo(false))
    },
      testM("waitUntil proceeds on variable change") {
        val evaluated = (for {
          remoteBoolVar <- createBoolVarZFlow
          _ <- waitUntilOnBoolVarZFlow(remoteBoolVar, 5L, 2L)
          bool <- remoteBoolVar.get
        } yield bool).evaluateTestInMem

        val result = for {
          f <- evaluated.fork
          _ <- TestClock.adjust(10.seconds)
          r <- f.join
        } yield r

        assertM(result)(equalTo(true))
      })

  val suite2 = suite("Test on RemoteVariable with live clock")(testM("waitUntil times out") {
    val evaluated: ZIO[Clock with Console, Nothing, Boolean] = (for {
      remoteBoolVar <- createBoolVarZFlow
      _ <- waitUntilOnBoolVarZFlow(remoteBoolVar, 1L, 100L)
      bool <- remoteBoolVar.get
    } yield bool).evaluateLiveInMem

    assertM(evaluated)(equalTo(false))
  },
    testM("waitUntil proceeds on variable change") {
      val evaluated: ZIO[Clock with Console, Nothing, Boolean] = (for {
        remoteBoolVar <- createBoolVarZFlow
        //TODO : This waits for timeoutDuration time
        _ <- waitUntilOnBoolVarZFlow(remoteBoolVar, 5L, 1L)
        bool <- remoteBoolVar.get
      } yield bool).evaluateLiveInMem

      assertM(evaluated)(equalTo(true))
    })

  val suite3 = suite("Test ZFlow.when")(testM("With RunActivity") {
    val a: ZFlow[Any, ActivityError, Unit] = for {
      remoteBool <- Remote(true)
      _ <- ZFlow.when(remoteBool)(ZFlow.RunActivity(12, mockActivity))
    } yield ()

    val result = for {
      _ <- a.evaluateTestInMem
      output <- TestConsole.output
    } yield output

    assertM(result)(equalTo(Vector("Activity processing\n")))
  })

  val suite4 = suite("More test on waitUntil")(testM("waitUntil and do-while") {
    def doWhileOnWaitUntil(boolVar: RemoteVariable[Boolean]) = ZFlow.doWhile({
      for {
        option <- boolVar.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
        loop <- option.isNone.toFlow
        _ <- ZFlow.when(loop)(ZFlow.RunActivity(12, mockActivity))
      } yield loop
    })

      val evaluated = (for {
        remoteBoolVar <- createBoolVarZFlow
        _ <- doWhileOnWaitUntil(remoteBoolVar)
      } yield ()).evaluateTestInMem

      val result: ZIO[TestClock with Clock with Console, ActivityError, Unit] = for {
        f <- evaluated.fork
        _ <- TestClock.adjust(10.seconds)
        _ <- f.join
      } yield ()

        assertM(result)(equalTo(()))
  })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("Testing interactions between ZFlows")(suite1, suite2, suite3)
}
