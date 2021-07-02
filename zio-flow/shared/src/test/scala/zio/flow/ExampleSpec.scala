package zio.flow

import zio.ZIO
import zio.clock.Clock
import zio.console.Console
import zio.duration.durationInt
import zio.flow.utils.CompiledZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.test.Assertion.equalTo
import zio.test._
import zio.test.environment.TestClock

object ExampleSpec extends DefaultRunnableSpec {

  val createBoolVarZFlow: ZFlow[Any, Nothing, Variable[Boolean]]                              = ZFlow.newVar("boolVariable", false)
  def setBoolVarToTrueAfterSleep(remoteBoolVar: RemoteVariable[Boolean], sleepDuration : Long): ZFlow[Any, Nothing, Unit] = for {
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
        _    <- waitUntilOnBoolVarZFlow(remoteBoolVar, 2L, 5L)
        bool    <- remoteBoolVar.get
      } yield bool).evaluateTestInMem

      val result = for {
        f <- evaluated.fork
        _ <- TestClock.adjust(10.seconds)
        r <- f.join
      } yield r

      assertM(result)(equalTo(false))
    },
      testM("waitUntil proceeds on variable change"){
        val evaluated = (for {
          remoteBoolVar <- createBoolVarZFlow
          _    <- waitUntilOnBoolVarZFlow(remoteBoolVar, 5L, 2L)
          bool    <- remoteBoolVar.get
        } yield bool).evaluateTestInMem

        val result = for {
          f <- evaluated.fork
          _ <- TestClock.adjust(10.seconds)
          r <- f.join
        } yield r

        assertM(result)(equalTo(true))
      })

  val suite2 = suite("Test on RemoteVariable with live clock")(testM("waitUntil times out"){
    val evaluated: ZIO[Clock with Console, Nothing, Boolean] = (for {
      _    <- ZFlow.log("Starts")
      remoteBoolVar <- createBoolVarZFlow
      _<- ZFlow.log("Before waitUntil. Should wait for 2 seconds now.")
      _    <- waitUntilOnBoolVarZFlow(remoteBoolVar, 1L, 100L)
      _ <- ZFlow.log("After waitUntil")
      bool    <- remoteBoolVar.get
    } yield bool).evaluateLiveInMem

    assertM(evaluated)(equalTo(false))
  },
    testM("waitUntil proceeds on variable change"){
      val evaluated: ZIO[Clock with Console, Nothing, Boolean] = (for {
        _    <- ZFlow.log("Starts")
        remoteBoolVar <- createBoolVarZFlow
        _<- ZFlow.log("Before waitUntil. Should wait for 2 seconds now.")
        //TODO : This waits for timeoutDuration time
        _    <- waitUntilOnBoolVarZFlow(remoteBoolVar, 5L, 1L)
        _ <- ZFlow.log("After waitUntil")
        bool    <- remoteBoolVar.get
      } yield bool).evaluateLiveInMem

      assertM(evaluated)(equalTo(true))
    })


  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any]             = suite("Basic test")(suite1, suite2)
}
