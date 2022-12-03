/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
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

import zio.{Chunk, Duration, ZNothing, durationInt}
import zio.flow.{ZFlow, _}
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore}
import zio.schema.{DeriveSchema, Schema}
import zio.test._

object RestartedFlowsSpec extends PersistentExecutorBaseSpec {
  override def flowSpec
    : Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore with Configuration, Any] =
    suite("Restarted flows")(
      testRestartFlowAndLogs("log-|-log") { break =>
        for {
          _ <- ZFlow.log("first")
          _ <- break
          _ <- ZFlow.log("second")
        } yield 1
      } { (result, logs1, logs2) =>
        assertTrue(
          result == 1,
          logs1.contains("first"),
          !logs1.contains("second"),
          logs2.contains("second"),
          !logs2.contains("first")
        )
      },
      testRestartFlowAndLogs("newVar[Int]-|-get") { break =>
        for {
          v      <- ZFlow.newVar[Int]("var1", 100)
          _      <- break
          result <- v.get
        } yield result
      } { (result, _, _) =>
        assertTrue(
          result == 100
        )
      },
      testRestartFlowAndLogs("newVar[TestData]-|-get") { break =>
        for {
          v      <- ZFlow.newVar[TestData]("var1", TestData("test", 100))
          _      <- break
          result <- v.get
        } yield result
      } { (result, _, _) =>
        assertTrue(
          result == TestData("test", 100)
        )
      },
      testRestartFlowAndLogs("provide(log-|-input) (string)") { break =>
        (for {
          _ <- ZFlow.log("before break")
          _ <- break
          v <- ZFlow.input[String]
          _ <- ZFlow.log(v)
        } yield v).provide("abc")
      } { (result, logs1, logs2) =>
        assertTrue(
          result == "abc",
          logs1.contains("before break"),
          !logs2.contains("before break"),
          logs2.contains("abc"),
          !logs1.contains("abc")
        )
      },
      testRestartFlowAndLogs("provide(log-|-input) (user-defined)") { break =>
        (for {
          _ <- break
          v <- ZFlow.input[TestData]
        } yield v).provide(TestData("abc", 123))
      } { (result, _, _) =>
        assertTrue(
          result == TestData("abc", 123)
        )
      },
      testRestartFlowAndLogs("newVar,ensuring(set-|-set),get") { break =>
        for {
          v <- ZFlow.newVar[Int]("testvar", 1)
          _ <- (
                 for {
                   _ <- v.set(10)
                   _ <- break
                   _ <- v.set(100)
                 } yield ()
               ).ensuring {
                 v.update(_ + 1)
               }
          r <- v.get
        } yield r
      } { (result, _, _) =>
        assertTrue(
          result == 101
        )
      },
      testRestartFlowAndLogs("fork-|-await") { break =>
        for {
          fiber <- (for {
                     _   <- ZFlow.log("fiber started")
                     now <- ZFlow.now
                     _   <- ZFlow.waitTill(now.plusSeconds(220L)) // wait 220s, must finish only after restart
                     _   <- ZFlow.log("fiber finished")
                   } yield 10).fork
          _      <- ZFlow.waitTill(Instant.ofEpochSecond(10L)) // wait for absolute T=10s
          _      <- break                                      // waits for 100s
          result <- fiber.await.timeout(Duration.ofSeconds(150L))
        } yield result
      } { (result, logs1, logs2) =>
        assertTrue(
          result == Some(Right(10)),
          logs1.contains("fiber started"),
          !logs1.contains("fiber finished"),
          !logs2.contains("fiber started"),
          logs2.contains("fiber finished")
        )
      },
      testRestartFlowAndLogs("fork transaction, wait for changed-|-change, await") { break =>
        for {
          variable <- ZFlow.newVar("var", 0)
          fiber <-
            (ZFlow.log("fiber started") *>
              ZFlow.transaction { tx =>
                for {
                  value <- variable.get
                  _     <- ZFlow.log("TX")
                  _     <- tx.retryUntil(value === 1)
                } yield value
              }).fork
          _      <- ZFlow.sleep(1.second)
          _      <- break
          _      <- ZFlow.log("Setting var to 1")
          _      <- variable.set(1)
          result <- fiber.await
        } yield result
      } { (result, logs1, logs2) =>
        assertTrue(
          result == Right(1),
          logs1.contains("fiber started"),
          logs1.contains("TX"),
          !logs2.contains("fiber started"),
          logs2.contains("TX"),
          logs2.contains("Setting var to 1")
        )
      },
      testRestartFlowAndLogs("flow recursion-|-flow recursion") { break =>
        ZFlow.recurseSimple[Any, ZNothing, Int](0) { case (value, rec) =>
          ZFlow.log(rs"recursion step ${value.toString}") *>
            ZFlow.ifThenElse(value === 5)(
              ifTrue = break *> rec(value + 1),
              ifFalse = ZFlow.ifThenElse(value === 10)(
                ifTrue = ZFlow.succeed(value),
                ifFalse = rec(value + 1)
              )
            )
        }
      } { (res, logs1, logs2) =>
        assertTrue(
          res == 10,
          logs1.filter(_.contains("recursion step")) == Chunk(
            "recursion step 0",
            "recursion step 1",
            "recursion step 2",
            "recursion step 3",
            "recursion step 4",
            "recursion step 5"
          ),
          logs2.filter(_.contains("recursion step")) == Chunk(
            "recursion step 6",
            "recursion step 7",
            "recursion step 8",
            "recursion step 9",
            "recursion step 10"
          )
        )
      }
    )

  case class TestData(a: String, b: Int)

  object TestData {
    implicit val schema: Schema[TestData] = DeriveSchema.gen
  }
}
