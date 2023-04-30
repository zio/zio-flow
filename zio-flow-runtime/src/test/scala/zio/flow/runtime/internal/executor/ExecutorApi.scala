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

package zio.flow.runtime.internal.executor

import zio._
import zio.flow.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.flow._
import zio.flow.runtime._
import zio.flow.runtime.internal.PersistentState
import zio.schema.{DynamicValue, Schema}
import zio.test.{Spec, TestClock, TestEnvironment, assertTrue}

import java.util.concurrent.TimeUnit

object ExecutorApi extends PersistentExecutorBaseSpec {
  override def flowSpec
    : Spec[TestEnvironment with PersistentState with DurableLog with KeyValueStore with Configuration, Any] =
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
      ),
      test("abort running flow") {
        for {
          curr       <- Clock.currentTime(TimeUnit.SECONDS)
          executor   <- ZIO.service[ZFlowExecutor]
          flow        = ZFlow.waitTill(Instant.ofEpochSecond(curr + 2L)).as(1)
          id          = FlowId("abort-running-flow")
          _          <- executor.start(id, flow)
          _          <- TestClock.adjust(1.second)
          statuses1  <- executor.getAll.runCollect
          _          <- executor.abort(id)
          _          <- TestClock.adjust(4.seconds)
          statuses2  <- executor.getAll.runCollect
          pollResult <- executor.poll(id)
        } yield assertTrue(
          statuses1 == Chunk(id -> FlowStatus.Running),
          statuses2 == Chunk(id -> FlowStatus.Done),
          pollResult == Some(Left(Left(ExecutorError.Interrupted)))
        )
      }.provide(
        Configuration.inMemory,
        ZFlowExecutor.defaultInMemoryJson
      ),
      test("pause/resume flow") {
        for {
          executor   <- ZIO.service[ZFlowExecutor]
          flow        = ZFlow.sleep(2.seconds) *> ZFlow.sleep(1.second).as(1)
          id          = FlowId("pause-running-flow")
          _          <- executor.start(id, flow)
          _          <- TestClock.adjust(1.second)
          statuses1  <- executor.getAll.runCollect
          _          <- executor.pause(id)
          _          <- TestClock.adjust(4.seconds)
          statuses2  <- executor.getAll.runCollect
          _          <- executor.resume(id)
          _          <- TestClock.adjust(1.second)
          statuses3  <- executor.getAll.runCollect
          pollResult <- executor.poll(id)
        } yield assertTrue(
          statuses1 == Chunk(id -> FlowStatus.Running),
          statuses2 == Chunk(id -> FlowStatus.Paused),
          statuses3 == Chunk(id -> FlowStatus.Done),
          pollResult == Some(Right(DynamicValue.fromSchemaAndValue(Schema[Int], 1)))
        )
      }.provide(
        Configuration.inMemory,
        ZFlowExecutor.defaultInMemoryJson
      ),
      test("get value from running flow") {
        for {
          executor <- ZIO.service[ZFlowExecutor]
          flow = for {
                   v <- ZFlow.newVar("testvar", 11)
                   _ <- v.update(_ + 1)
                   _ <- ZFlow.sleep(2.seconds)
                   r <- v.updateAndGet(_ + 1)
                 } yield r
          id           = FlowId("get-var-1")
          _           <- executor.start(id, flow)
          _           <- TestClock.adjust(1.second)
          result      <- executor.getVariable(id, RemoteVariableName("testvar"))
          _           <- TestClock.adjust(2.second)
          finalResult <- executor.poll(id)
        } yield assertTrue(
          result == Some(DynamicValue.fromSchemaAndValue(Schema[Int], 12)),
          finalResult == Some(Right(DynamicValue.fromSchemaAndValue(Schema[Int], 13)))
        )
      }.provide(
        Configuration.inMemory,
        ZFlowExecutor.defaultInMemoryJson
      ),
      test("get value from stopped flow") {
        for {
          executor <- ZIO.service[ZFlowExecutor]
          flow = for {
                   v <- ZFlow.newVar("testvar", 11)
                   _ <- v.update(_ + 1)
                   _ <- ZFlow.sleep(2.seconds)
                   r <- v.updateAndGet(_ + 1)
                 } yield r
          id           = FlowId("get-var-2")
          _           <- executor.start(id, flow)
          _           <- TestClock.adjust(5.second)
          finalResult <- executor.poll(id)
          result      <- executor.getVariable(id, RemoteVariableName("testvar"))
        } yield assertTrue(
          result == Some(DynamicValue.fromSchemaAndValue(Schema[Int], 13)),
          finalResult == Some(Right(DynamicValue.fromSchemaAndValue(Schema[Int], 13)))
        )
      }.provide(
        Configuration.inMemory,
        ZFlowExecutor.defaultInMemoryJson
      ),
      test("set value for running flow, unblocking suspended") {
        for {
          executor <- ZIO.service[ZFlowExecutor]
          flow = for {
                   v <- ZFlow.newVar("testvar", 11)
                   _ <- ZFlow.log("waiting")
                   _ <- v.waitUntil(_ === 100)
                   _ <- ZFlow.log("unblocked")
                   r <- v.updateAndGet(_ + 1)
                 } yield r
          id       = FlowId("get-var-3")
          _       <- executor.start(id, flow)
          _       <- TestClock.adjust(1.second)
          initial <- executor.getVariable(id, RemoteVariableName("testvar"))
          _ <-
            executor.setVariable(id, RemoteVariableName("testvar"), DynamicValue.fromSchemaAndValue(Schema[Int], 100))
          _           <- TestClock.adjust(1.second)
          _           <- TestClock.adjust(1.second)
          _           <- TestClock.adjust(1.second)
          finalResult <- executor.poll(id)
        } yield assertTrue(
          initial == Some(DynamicValue.fromSchemaAndValue(Schema[Int], 11)),
          finalResult == Some(Right(DynamicValue.fromSchemaAndValue(Schema[Int], 101)))
        )
      }.provide(
        Configuration.inMemory,
        ZFlowExecutor.defaultInMemoryJson
      )
    )
}
