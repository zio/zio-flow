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

import zio.flow.runtime.internal._
import zio.flow._
import zio.test._
import zio._
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore}

object GarbageCollectionSpec extends PersistentExecutorBaseSpec {
  override def flowSpec
    : Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore with Configuration, Any] =
    suite("Garbage Collection")(
      testGCFlow("Unused simple variable gets deleted") { break =>
        for {
          v1 <- ZFlow.newVar[Int]("v1", 1)
          _  <- ZFlow.newVar[Int]("v2", 1)
          _  <- v1.set(10)
          _  <- break
          _  <- v1.set(100)
          r  <- v1.get
        } yield r
      } { (result, vars) =>
        assertTrue(
          result == 100,
          vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v1"),
              RemoteVariableScope.TopLevel(FlowId("Unused simple variable gets deleted"))
            )
          ),
          !vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v2"),
              RemoteVariableScope.TopLevel(FlowId("Unused simple variable gets deleted"))
            )
          )
        )
      },
      testGCFlow("Variable referenced by flow is kept") { break =>
        for {
          v1    <- ZFlow.newVar[Int]("v1", 1)
          v2    <- ZFlow.newVar[ZFlow[Any, ZNothing, Int]]("v2", v1.updateAndGet(_ + 1))
          _     <- break
          flow2 <- v2.get
          r     <- ZFlow.unwrap(flow2)
        } yield r
      } { (result, vars) =>
        assertTrue(
          result == 2,
          vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v1"),
              RemoteVariableScope.TopLevel(FlowId("Variable referenced by flow is kept"))
            )
          ),
          vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v2"),
              RemoteVariableScope.TopLevel(FlowId("Variable referenced by flow is kept"))
            )
          )
        )
      },
      testGCFlow("Chain of computation") { break =>
        for {
          a <- ZFlow.newVar[Int]("v1", 1)
          b <- a.get.map(_ + 1)
          c <- b + 1
          _ <- break
          r <- c + 1
        } yield r
      } { (result, vars) =>
        assertTrue(
          result == 4,
          !vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v1"),
              RemoteVariableScope.TopLevel(FlowId("Chain of computation"))
            )
          )
        )
      },
      testGCFlow("Shadowed variable of fiber is kept") { break =>
        for {
          v1 <- ZFlow.newVar[Int]("v1", 1)
          fiber <- (
                     for {
                       v1 <- ZFlow.newVar[Int]("v1", 2)
                       _  <- ZFlow.sleep(1.day)
                     } yield v1
                   ).fork
          _ <- break
          _ <- fiber.interrupt
          r <- v1.get
        } yield r
      } { (result, vars) =>
        assertTrue(
          result == 1,
          vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v1"),
              RemoteVariableScope.TopLevel(FlowId("Shadowed variable of fiber is kept"))
            )
          ),
          vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v1"),
              RemoteVariableScope.Fiber(
                FlowId("fork0"),
                RemoteVariableScope.TopLevel(FlowId("Shadowed variable of fiber is kept"))
              )
            )
          )
        )
      },
      testGCFlow("Shadowed variable of interrupted fiber is deleted") { break =>
        for {
          v1 <- ZFlow.newVar[Int]("v1", 1)
          fiber <- (
                     for {
                       v1 <- ZFlow.newVar[Int]("v1", 2)
                       _  <- ZFlow.sleep(1.day)
                     } yield v1
                   ).fork
          _ <- fiber.interrupt
          _ <- break
          r <- v1.get
        } yield r
      } { (result, vars) =>
        assertTrue(
          result == 1,
          vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v1"),
              RemoteVariableScope.TopLevel(FlowId("Shadowed variable of interrupted fiber is deleted"))
            )
          ),
          !vars.contains(
            ScopedRemoteVariableName(
              RemoteVariableName("v1"),
              RemoteVariableScope.Fiber(
                FlowId("fork0"),
                RemoteVariableScope.TopLevel(FlowId("Shadowed variable of interrupted fiber is deleted"))
              )
            )
          )
        )
      },
      testGCFlow("Old version of variables gets cleaned up") { break =>
        for {
          v1 <- ZFlow.newVar[Int]("v1", 1)
          v2 <- ZFlow.newVar[Int]("v2", 1)
          fiber1 <- (for {
                      _ <- v1.update(_ + 1)
                      _ <- v1.update(_ + 1)
                      _ <- v1.update(_ + 1)
                      _ <- v1.update(_ + 1)
                      _ <- v1.update(_ + 1)
                      _ <- v1.update(_ + 1)
                      _ <- v1.update(_ + 1)
                      _ <- v1.update(_ + 1)
                      _ <- ZFlow.sleep(100.seconds)
                      r <- v1.get
                    } yield r).fork
          fiber2 <- (for {
                      _ <- v2.update(_ + 1)
                      _ <- v2.update(_ + 1)
                      _ <- v2.update(_ + 1)
                      _ <- v2.update(_ + 1)
                      _ <- v2.update(_ + 1)
                      _ <- v2.update(_ + 1)
                      _ <- ZFlow.sleep(100.seconds)
                      r <- v2.get
                    } yield r).fork
          _  <- break
          r1 <- fiber1.await
          r2 <- fiber2.await
        } yield (r1, r2)
      } { (result, vars) =>
        val sv1 = ScopedRemoteVariableName(
          RemoteVariableName("v1"),
          RemoteVariableScope.TopLevel(FlowId("Old version of variables gets cleaned up"))
        )
        val sv2 = ScopedRemoteVariableName(
          RemoteVariableName("v2"),
          RemoteVariableScope.TopLevel(FlowId("Old version of variables gets cleaned up"))
        )
        assertTrue(
          result._1 == Right(9),
          result._2 == Right(7),
          vars.getOrElse(sv1, Chunk.empty).length == 1,
          vars.getOrElse(sv2, Chunk.empty).length == 1
        )
      }
    )
}
