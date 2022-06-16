package zio.flow

import zio.flow.internal.{DurableLog, DurablePromise, IndexedStore}
import zio.{Promise, ZEnvironment, ZIO}
import zio.flow.serialization._
import zio.test.{TestEnvironment, ZIOSpecDefault, Spec, assertTrue}

import java.io.IOException

object DurablePromiseSpec extends ZIOSpecDefault {
  private val execEnv = ExecutionEnvironment(Serializer.json, Deserializer.json)

  override def spec: Spec[TestEnvironment, Any] =
    suite("DurablePromise")(
      test("Can't fail, await success") {
        val promise = DurablePromise.make[Nothing, Int]("dp-1")
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log, execEnv)
          result <- Promise.make[IOException, Either[Nothing, Int]]
          fiber  <- promise.awaitEither.provideEnvironment(env).intoPromise(result).fork
          _      <- promise.succeed(100).provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Right(100))
      },
      test("Can fail, await success") {
        val promise = DurablePromise.make[String, Int]("dp-2")
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log, execEnv)
          result <- Promise.make[IOException, Either[String, Int]]
          fiber  <- promise.awaitEither.provideEnvironment(env).intoPromise(result).fork
          _      <- promise.succeed(100).provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Right(100))
      },
      test("Can fail, await failure") {
        val promise = DurablePromise.make[String, Int]("dp-3")
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log, execEnv)
          result <- Promise.make[IOException, Either[String, Int]]
          fiber  <- promise.awaitEither.provideEnvironment(env).intoPromise(result).fork
          _      <- promise.fail("not good").provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Left("not good"))
      },
      test("Can't fail, poll success") {
        val promise = DurablePromise.make[Nothing, Int]("dp-4")
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log, execEnv)
          before <- promise.poll.provideEnvironment(env)
          _      <- promise.succeed(100).provideEnvironment(env)
          after  <- promise.poll.provideEnvironment(env)
        } yield assertTrue(before == None) && assertTrue(after == Some(Right(100)))
      },
      test("Can fail, poll failure") {
        val promise = DurablePromise.make[String, Int]("dp-5")
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log, execEnv)
          before <- promise.poll.provideEnvironment(env)
          _      <- promise.fail("not good").provideEnvironment(env)
          after  <- promise.poll.provideEnvironment(env)
        } yield assertTrue(before == None) && assertTrue(after == Some(Left("not good")))
      }
    ).provide(
      DurableLog.live,
      IndexedStore.inMemory
    )
}
