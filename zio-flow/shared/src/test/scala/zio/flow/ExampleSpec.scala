package zio.flow

import zio.clock.Clock
import zio.console.Console
import zio.duration.durationInt
import zio.flow.utils.CompiledZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.test.Assertion.equalTo
import zio.test._
import zio.test.environment.TestClock

object ExampleSpec extends DefaultRunnableSpec {

  val createBoolVarZFlow: ZFlow[Any, Nothing, Variable[Boolean]]                              = ZFlow.newVar("boolVariable", false)
  def setBoolVarToTrueAfterSleep(boolVar: RemoteVariable[Boolean]): ZFlow[Any, Nothing, Unit] = for {
    _ <- ZFlow.sleep(Remote.ofSeconds(2L)).timeout(Remote.ofSeconds(1L))
    _ <- boolVar.set(true)
  } yield ()

  def waitUntilOnBoolVarZFlow(boolVar: RemoteVariable[Boolean]): ZFlow[Any, Nothing, Boolean] = for {
    _ <- setBoolVarToTrueAfterSleep(boolVar).fork
    _ <- boolVar.waitUntil(_ === true).timeout(Remote.ofSeconds(2L))
    b <- boolVar.get
  } yield b

  val suite1: Spec[Clock with Console with TestClock, TestFailure[Nothing], TestSuccess] =
    suite("Bool var test")(testM("Sleep, then set to true") {
      val b = (for {
        _    <- ZFlow.log("Start")
        bool <- createBoolVarZFlow
        _    <- ZFlow.log("Before flow")
        _    <- setBoolVarToTrueAfterSleep(bool)
        _    <- ZFlow.log("After flow")
        b    <- bool.get
      } yield b).evaluateTestInMem

      val result = for {
        f <- b.fork
        _ <- TestClock.adjust(3.seconds)
        r <- f.join
      } yield r

      assertM(result)(equalTo(true))
    })
  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any]             = suite("Basic test")(suite1)
}
