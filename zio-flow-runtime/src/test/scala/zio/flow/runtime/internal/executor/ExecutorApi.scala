package zio.flow.runtime.internal.executor

import zio._
import zio.flow.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow._
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore, ZFlowExecutor}
import zio.schema.Schema
import zio.test.{Spec, TestClock, TestEnvironment, assertTrue}

import java.util.concurrent.TimeUnit

object ExecutorApi extends PersistentExecutorBaseSpec {
  override def flowSpec
    : Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore with Configuration, Any] =
    suite("PersistentExecutor API")(
      test("poll successful result") {
        for {
          _     <- TestClock.adjust(5.seconds)
          flow   = ZFlow.now
          fiber <- flow.evaluateTestStartAndPoll("now", 1.second).fork
          _     <- TestClock.adjust(1.second)
          result <- fiber.await.map {
                      case Exit.Success(Some(r)) =>
                        val mapped = r.flatMap(_.toTypedValue(Schema[Instant]))
                        assertTrue(
                          mapped == Right(java.time.Instant.ofEpochSecond(5L))
                        )
                      case _ =>
                        assertTrue(false)
                    }
        } yield result
      },
      test("poll running flow") {
        for {
          curr <- Clock.currentTime(TimeUnit.SECONDS)
          flow = for {
                   flow1 <- ZFlow.waitTill(Instant.ofEpochSecond(curr + 2L)).as(1).fork
                   flow2 <- ZFlow.waitTill(Instant.ofEpochSecond(curr + 3L)).as(2).fork
                   r1    <- flow1.await
                   r2    <- flow2.await
                   _     <- ZFlow.log(r1.toString)
                 } yield (r1.toOption, r2.toOption)
          fiber  <- flow.evaluateTestStartAndPoll("wait-poll-early", 0.seconds).fork
          _      <- TestClock.adjust(1.seconds)
          result <- fiber.join
        } yield assertTrue(result.isEmpty)
      },
      test("poll completed flow") {
        val fId = FlowId("waitTill-complete")
        ZIO.scoped {
          val flow = for {
            _   <- ZFlow.sleep(Remote(1.second))
            now <- ZFlow.now
          } yield now
          for {
            executor <- ZIO.service[ZFlowExecutor]
            _        <- executor.restartAll()
            _        <- executor.start(fId, flow)
            _        <- TestClock.adjust(2.seconds)
            resultF  <- executor.poll(fId).fork
            _        <- TestClock.adjust(1.seconds)
            result   <- resultF.join
          } yield assertTrue(result.isDefined)
        }
      }.provide(
        Configuration.inMemory,
        ZFlowExecutor.defaultInMemoryJson
      ),
      test("delete running flow") {
        for {
          curr     <- Clock.currentTime(TimeUnit.SECONDS)
          executor <- ZIO.service[ZFlowExecutor]
          flow = for {
                   flow1 <- ZFlow.waitTill(Instant.ofEpochSecond(curr + 2L)).as(1).fork
                   r1    <- flow1.await
                   _     <- ZFlow.log(r1.toString)
                 } yield ()
          id      = FlowId("delete-running-flow")
          _      <- executor.start(id, flow)
          result <- executor.delete(id).exit
        } yield assertTrue(result.isFailure)
      }.provide(
        Configuration.inMemory,
        ZFlowExecutor.defaultInMemoryJson
      ),
      test("delete finished flow") {
        for {
          curr     <- Clock.currentTime(TimeUnit.SECONDS)
          executor <- ZIO.service[ZFlowExecutor]
          flow = for {
                   flow1 <- ZFlow.waitTill(Instant.ofEpochSecond(curr + 2L)).as(1).fork
                   r1    <- flow1.await
                   _     <- ZFlow.log(r1.toString)
                 } yield ()
          id      = FlowId("delete-running-flow")
          _      <- executor.start(id, flow)
          _      <- TestClock.adjust(5.seconds)
          result <- executor.delete(id).exit
        } yield assertTrue(result.isSuccess)
      }.provide(
        Configuration.inMemory,
        ZFlowExecutor.defaultInMemoryJson
      )
    )
}
