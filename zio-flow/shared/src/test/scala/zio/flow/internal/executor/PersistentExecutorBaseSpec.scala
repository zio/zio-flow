package zio.flow.internal.executor

import zio.flow.internal._
import zio.flow.mock.MockedOperation
import zio.flow.serialization.{Deserializer, Serializer}
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow._
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore}
import zio.schema.Schema
import zio.test.{Live, Spec, TestClock, TestEnvironment, TestResult}
import zio.{
  Cause,
  Chunk,
  Clock,
  Duration,
  Exit,
  FiberFailure,
  FiberId,
  FiberRefs,
  LogLevel,
  LogSpan,
  Promise,
  Queue,
  Runtime,
  Scope,
  Trace,
  Unsafe,
  ZIO,
  ZLayer,
  ZLogger,
  durationInt
}

import java.util.concurrent.atomic.AtomicInteger

trait PersistentExecutorBaseSpec extends ZIOFlowBaseSpec {

  private val counter      = new AtomicInteger(0)
  protected val unit: Unit = ()

  def flowSpec: Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore with Configuration, Any]

  override def spec: Spec[TestEnvironment with Scope, Any] =
    flowSpec
      .provideSome[TestEnvironment](
        IndexedStore.inMemory,
        DurableLog.layer,
        KeyValueStore.inMemory,
        Configuration.inMemory,
        Runtime.addLogger(ZLogger.default.filterLogLevel(_ == LogLevel.Debug).map(_.foreach(println)))
      )

  protected def testFlowAndLogsExit[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration]
  )(
    flow: ZFlow[Any, E, A]
  )(assert: (Exit[E, A], Chunk[String]) => TestResult, mock: MockedOperation) =
    test(label) {
      for {
        logQueue <- Queue.unbounded[String]
        runtime  <- ZIO.runtime[Any]
        logger = new ZLogger[String, Any] {

                   override def apply(
                     trace: Trace,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: FiberRefs,
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = Unsafe.unsafe { implicit u =>
                     val msg = message()
                     runtime.unsafe.run(logQueue.offer(message()).unit).getOrThrowFiberFailure()
                     msg
                   }
                 }
        fiber <-
          flow
            .evaluateTestPersistent("wf" + counter.incrementAndGet().toString, mock)
            .provideSomeLayer[DurableLog with KeyValueStore with Configuration](Runtime.addLogger(logger))
            .exit
            .fork
        flowResult <- periodicAdjustClock match {
                        case Some(value) => waitAndPeriodicallyAdjustClock("flow result", 1.second, value)(fiber.join)
                        case None        => fiber.join
                      }
        logLines <- logQueue.takeAll
      } yield assert(flowResult, logLines)
    }

  protected def testFlowAndLogs[E: Schema, A: Schema](
    label: String,
    periodicAdjustClock: Option[Duration] = None
  )(flow: ZFlow[Any, E, A])(assert: (A, Chunk[String]) => TestResult, mock: MockedOperation = MockedOperation.Empty) =
    testFlowAndLogsExit(label, periodicAdjustClock)(flow)(
      { case (exit, logs) =>
        exit.foldExit(cause => throw FiberFailure(cause), result => assert(result, logs))
      },
      mock
    )

  protected def testFlow[E: Schema, A: Schema](label: String, periodicAdjustClock: Option[Duration] = None)(
    flow: ZFlow[Any, E, A]
  )(
    assert: A => TestResult,
    mock: MockedOperation = MockedOperation.Empty
  ) =
    testFlowAndLogs(label, periodicAdjustClock)(flow)({ case (result, _) => assert(result) }, mock)

  protected def testFlowExit[E: Schema, A: Schema](label: String, periodicAdjustClock: Option[Duration] = None)(
    flow: ZFlow[Any, E, A]
  )(
    assert: Exit[E, A] => TestResult,
    mock: MockedOperation = MockedOperation.Empty
  ) =
    testFlowAndLogsExit(label, periodicAdjustClock)(flow)({ case (result, _) => assert(result) }, mock)

  protected def testRestartFlowAndLogs[E: Schema, A: Schema](
    label: String
  )(flow: ZFlow[Any, Nothing, Unit] => ZFlow[Any, E, A])(assert: (A, Chunk[String], Chunk[String]) => TestResult) =
    test(label) {
      for {
        _            <- ZIO.logDebug(s"=== testRestartFlowAndLogs $label started === ")
        logQueue     <- Queue.unbounded[String]
        runtime      <- ZIO.runtime[Any]
        breakPromise <- Promise.make[Nothing, Unit]
        logger = new ZLogger[String, Any] {

                   override def apply(
                     trace: Trace,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: FiberRefs,
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = Unsafe.unsafe { implicit u =>
                     val msg = message()
                     runtime.unsafe.run {
                       msg match {
                         case "!!!BREAK!!!" => breakPromise.succeed(())
                         case _             => logQueue.offer(msg).unit
                       }
                     }.getOrThrowFiberFailure()
                     msg
                   }
                 }
        results <- {
          val break: ZFlow[Any, Nothing, Unit] =
            (ZFlow.log("!!!BREAK!!!") *>
              ZFlow.waitTill(Instant.ofEpochSecond(100L)))
          val finalFlow = flow(break)
          for {
            fiber1 <- finalFlow
                        .evaluateTestPersistent(label)
                        .provideSomeLayer[DurableLog with KeyValueStore with Configuration](Runtime.addLogger(logger))
                        .fork
            _ <- ZIO.logDebug(s"Adjusting clock by 20s")
            _ <- TestClock.adjust(20.seconds)
            _ <- waitAndPeriodicallyAdjustClock("break event", 1.second, 10.seconds) {
                   breakPromise.await
                 }
            _         <- ZIO.logDebug("Interrupting executor")
            _         <- fiber1.interrupt
            logLines1 <- logQueue.takeAll
            fiber2 <- finalFlow
                        .evaluateTestPersistent(label)
                        .provideSomeLayer[DurableLog with KeyValueStore with Configuration](Runtime.addLogger(logger))
                        .fork
            _ <- ZIO.logDebug(s"Adjusting clock by 200s")
            _ <- TestClock.adjust(200.seconds)
            result <- waitAndPeriodicallyAdjustClock("executor to finish", 1.second, 10.seconds) {
                        fiber2.join
                      }
            logLines2 <- logQueue.takeAll
          } yield (result, logLines1, logLines2)
        }
      } yield assert.tupled(results)
    }

  protected def testGCFlow[E: Schema, A: Schema](
    label: String
  )(flow: ZFlow[Any, Nothing, Unit] => ZFlow[Any, E, A])(assert: (A, Set[ScopedRemoteVariableName]) => TestResult) =
    test(label) {
      for {
        _            <- ZIO.logDebug(s"=== testGCFlow $label started === ")
        runtime      <- ZIO.runtime[Any]
        breakPromise <- Promise.make[Nothing, Unit]
        logger = new ZLogger[String, Any] {

                   override def apply(
                     trace: Trace,
                     fiberId: FiberId,
                     logLevel: LogLevel,
                     message: () => String,
                     cause: Cause[Any],
                     context: FiberRefs,
                     spans: List[LogSpan],
                     annotations: Map[String, String]
                   ): String = Unsafe.unsafe { implicit u =>
                     val msg = message()
                     runtime.unsafe.run {
                       msg match {
                         case "!!!BREAK!!!" => breakPromise.succeed(())
                         case _             => ZIO.unit
                       }
                     }.getOrThrowFiberFailure()
                     msg
                   }
                 }
        results <- {
          val break: ZFlow[Any, Nothing, Unit] =
            (ZFlow.log("!!!BREAK!!!") *>
              ZFlow.waitTill(Instant.ofEpochSecond(100L)))
          val finalFlow = flow(break)

          ZIO.scoped[Live with DurableLog with KeyValueStore with Configuration] {
            for {
              pair <- finalFlow
                        .submitTestPersistent(label)
                        .provideSomeLayer[Scope with DurableLog with KeyValueStore with Configuration](
                          Runtime.addLogger(logger)
                        )
              (executor, fiber) = pair
              _                <- ZIO.logDebug(s"Adjusting clock by 20s")
              _                <- TestClock.adjust(20.seconds)
              _ <- waitAndPeriodicallyAdjustClock("break event", 1.second, 10.seconds) {
                     breakPromise.await
                   }
              _ <- ZIO.logDebug("Forcing GC")
              _ <- executor.forceGarbageCollection()
              vars <-
                RemoteVariableKeyValueStore.allStoredVariables.runCollect
                  .provideSome[DurableLog with KeyValueStore](
                    RemoteVariableKeyValueStore.live,
                    Configuration.inMemory,
                    ZLayer(
                      ZIO
                        .service[Configuration]
                        .map(config => ExecutionEnvironment(Serializer.json, Deserializer.json, config))
                    ) // TODO: this should not be recreated here
                  )
              _ <- ZIO.logDebug(s"Adjusting clock by 200s")
              _ <- TestClock.adjust(200.seconds)
              result <- waitAndPeriodicallyAdjustClock("executor to finish", 1.second, 10.seconds) {
                          fiber.join
                        }
            } yield (result, vars.toSet)
          }
        }
      } yield assert.tupled(results)
    }

  protected def waitAndPeriodicallyAdjustClock[E, A](
    description: String,
    duration: Duration,
    adjustment: Duration
  )(wait: ZIO[Any, E, A]): ZIO[Live, E, A] =
    for {
      _           <- ZIO.logDebug(s"Waiting for $description")
      maybeResult <- wait.timeout(1.second).withClock(Clock.ClockLive)
      result <- maybeResult match {
                  case Some(result) => ZIO.succeed(result)
                  case None =>
                    for {
                      _      <- ZIO.logDebug(s"Adjusting clock by $adjustment")
                      _      <- TestClock.adjust(adjustment)
                      now    <- Clock.instant
                      _      <- ZIO.logDebug(s"T=$now")
                      result <- waitAndPeriodicallyAdjustClock(description, duration, adjustment)(wait)
                    } yield result
                }
    } yield result
}
