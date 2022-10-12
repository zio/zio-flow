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
      }
    )
}
