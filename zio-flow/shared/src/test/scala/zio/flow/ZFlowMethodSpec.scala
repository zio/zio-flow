package zio.flow

import zio._
import zio.flow.utils.MockHelpers.mockActivity
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow._
import zio.test.Assertion.equalTo
import zio.test._

object ZFlowMethodSpec extends ZIOSpecDefault {

  def setBoolVarAfterSleep(
    remoteBoolVar: Remote[RemoteVariable[Boolean]],
    sleepDuration: Long,
    value: Boolean
  ): ZFlow[Any, Nothing, Unit] = for {
    _ <- ZFlow.sleep(Remote.ofSeconds(sleepDuration))
    _ <- remoteBoolVar.set(value)
  } yield ()

  def waitUntilOnBoolVarZFlow(
    remoteBoolVar: Remote[RemoteVariable[Boolean]],
    timeoutDuration: Long,
    sleepDuration: Long
  ): ZFlow[Any, Nothing, Boolean] = for {
    _    <- setBoolVarAfterSleep(remoteBoolVar, sleepDuration, true).fork
    _    <- remoteBoolVar.waitUntil(_ === true).timeout(Remote.ofSeconds(timeoutDuration))
    bool <- remoteBoolVar.get
  } yield bool

  val suite1: Spec[Clock with Console with TestClock, TestFailure[
    Nothing
  ], TestSuccess] =
    suite("Test `waitUnit` with `timeout`")(
      test("`timeout` duration met") {
        val evaluated = (for {
          remoteBoolVar <- ZFlow.newVar("boolVariable", false)
          _             <- waitUntilOnBoolVarZFlow(remoteBoolVar, 2L, 5L)
          bool          <- remoteBoolVar.get
        } yield bool).evaluateTestInMem

        val result = for {
          f <- evaluated.fork
          _ <- TestClock.adjust(5.seconds)
          r <- f.join
        } yield r

        assertM(result)(equalTo(false))
      },
      test("`waitUntil` proceeds on variable change") {
        val evaluated = (for {
          remoteBoolVar <- ZFlow.newVar("boolVariable", false)
          _             <- waitUntilOnBoolVarZFlow(remoteBoolVar, 5L, 2L)
          bool          <- remoteBoolVar.get
        } yield bool).evaluateTestInMem

        val result = for {
          f <- evaluated.fork
          _ <- TestClock.adjust(5.seconds)
          r <- f.join
        } yield r

        assertM(result)(equalTo(true))
      }
    )

  val suite2: Spec[Clock with Console, TestFailure[Nothing], TestSuccess] =
    suite("Test `waitUnit` with `timeout` - uses live clock")(
      test("`timeout` duration met") {
        val evaluated: ZIO[Clock with Console, Nothing, Boolean] = (for {
          remoteBoolVar <- ZFlow.newVar("boolVariable", false)
          _             <- waitUntilOnBoolVarZFlow(remoteBoolVar, 1L, 100L)
          bool          <- remoteBoolVar.get
        } yield bool).evaluateLiveInMem

        assertM(evaluated)(equalTo(false))
      },
      test("waitUntil proceeds on variable change") {
        val evaluated: ZIO[Clock with Console, Nothing, Boolean] = (for {
          remoteBoolVar <- ZFlow.newVar("boolVariable", false)
          //TODO : This waits for timeoutDuration time
          _    <- waitUntilOnBoolVarZFlow(remoteBoolVar, 2L, 1L)
          bool <- remoteBoolVar.get
        } yield bool).evaluateLiveInMem

        assertM(evaluated)(equalTo(true))
      }
    )

  val suite3: Spec[Clock with Console with TestConsole, TestFailure[ActivityError], TestSuccess] =
    suite("Test ZFlow.when")(test("With RunActivity") {
      val whenZflow: ZFlow[Any, ActivityError, Unit] = for {
        remoteBool <- Remote(true)
        _          <- ZFlow.when(remoteBool)(ZFlow.RunActivity(12, mockActivity))
      } yield ()

      val result = for {
        _      <- whenZflow.evaluateTestInMem
        output <- TestConsole.output
      } yield output

      assertM(result)(equalTo(Vector("Activity processing\n")))
    })

  val suite5: Spec[Clock with Console with TestClock with Clock with Console with TestConsole, TestFailure[
    Throwable
  ], TestSuccess] =
    suite("Test Iterate")(
      test("Iterate a fixed number of times") {
        val iterateZflow: ZFlow[Any, ActivityError, Int] =
          ZFlow.Iterate( // TODO: iterate constructor that accepts function
            Remote(12),
            (remoteInt: Remote[Int]) =>
              Remote(for {
                rInt <- remoteInt + 1
                _    <- ZFlow.RunActivity(12, mockActivity)
              } yield rInt),
            (r: Remote[Int]) => r !== 15
          )

        val result = for {
          _      <- iterateZflow.evaluateTestInMem
          output <- TestConsole.output
        } yield output

        assertM(result)(equalTo(Vector("Activity processing\n", "Activity processing\n", "Activity processing\n")))
      },
      test("Iterate on combination of `timeout` and `waitUntil`") {
        val boolVarZFlow: ZFlow[Any, Nothing, Remote.Variable[Boolean]] = ZFlow.newVar("boolVarForIterate", true)

        def iterateZflow(boolVar: Remote[RemoteVariable[Boolean]]): ZFlow[Any, ActivityError, Boolean] = ZFlow.Iterate(
          Remote(true),
          (_: Remote[Boolean]) =>
            Remote(for {
              bool <- boolVar
              _    <- setBoolVarAfterSleep(bool, 5, false).fork
              _    <- bool.waitUntil(_ === false).timeout(Remote.ofSeconds(1L))
              loop <- bool.get
              _    <- ZFlow.RunActivity(12, mockActivity)
            } yield loop),
          (b: Remote[Boolean]) => b
        )

        val evaluated: ZIO[Clock with Console, ActivityError, Boolean] = (for {
          b         <- boolVarZFlow
          evaluated <- iterateZflow(b)
        } yield evaluated).evaluateTestInMem

        val result = for {
          f <- evaluated.fork
          _ <- TestClock.adjust(10.seconds)
          r <- f.join
        } yield r

        assertM(result)(equalTo(false))
      }
    )

  val suite4: Spec[TestClock with Clock with Console, TestFailure[ActivityError], TestSuccess] =
    suite("More test on waitUntil")(test("waitUntil and do-while") {
      def doWhileOnWaitUntil(boolVar: Remote[Remote.Variable[Boolean]]): ZFlow[Any, ActivityError, Boolean] =
        ZFlow.doWhile({
          for {
            _    <- boolVar.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
            loop <- boolVar.get
            _    <- ZFlow.when(loop)(ZFlow.RunActivity(12, mockActivity))
          } yield loop
        })

      val evaluated: ZIO[Clock with Console, ActivityError, Any] = (for {
        remoteBoolVar <- ZFlow.newVar("boolVar", false)
        _             <- setBoolVarAfterSleep(remoteBoolVar, 5L, true).fork
        b             <- doWhileOnWaitUntil(remoteBoolVar)
      } yield b).evaluateTestInMem

      val result: ZIO[TestClock with Clock with Console, ActivityError, Unit] = for {
        f <- evaluated.fork
        _ <- TestClock.adjust(10.seconds)
        _ <- f.join
      } yield ()

      assertM(result)(equalTo(()))
    })

  override def spec =
    suite("Testing interactions between ZFlows")(suite1, suite2, suite3, suite4, suite5)
}
