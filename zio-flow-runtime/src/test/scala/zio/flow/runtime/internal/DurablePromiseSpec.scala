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

package zio.flow.runtime.internal

import zio.flow._
import zio.flow.runtime.{DurableLog, DurablePromise, ExecutorError, IndexedStore}
import zio.flow.serialization._
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}
import zio.{Promise, ZEnvironment, ZIO}

object DurablePromiseSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("DurablePromise")(
      test("Can't fail, await success") {
        val promise = DurablePromise.make[Nothing, Int](PromiseId("dp-1"))
        for {
          log    <- ZIO.service[DurableLog]
          config <- ZIO.service[Configuration]
          env     = ZEnvironment(log, ExecutionEnvironment(Serializer.json, Deserializer.json, config))
          result <- Promise.make[ExecutorError, Either[Nothing, Int]]
          fiber  <- promise.awaitEither.provideEnvironment(env).intoPromise(result).fork
          _      <- promise.succeed(100).provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Right(100))
      },
      test("Can fail, await success") {
        val promise = DurablePromise.make[String, Int](PromiseId("dp-2"))
        for {
          log    <- ZIO.service[DurableLog]
          config <- ZIO.service[Configuration]
          env     = ZEnvironment(log, ExecutionEnvironment(Serializer.json, Deserializer.json, config))
          result <- Promise.make[ExecutorError, Either[String, Int]]
          fiber  <- promise.awaitEither.provideEnvironment(env).intoPromise(result).fork
          _      <- promise.succeed(100).provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Right(100))
      },
      test("Can fail, await failure") {
        val promise = DurablePromise.make[String, Int](PromiseId("dp-3"))
        for {
          log    <- ZIO.service[DurableLog]
          config <- ZIO.service[Configuration]
          env     = ZEnvironment(log, ExecutionEnvironment(Serializer.json, Deserializer.json, config))
          result <- Promise.make[ExecutorError, Either[String, Int]]
          fiber  <- promise.awaitEither.provideEnvironment(env).intoPromise(result).fork
          _      <- promise.fail("not good").provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Left("not good"))
      },
      test("Can't fail, poll success") {
        val promise = DurablePromise.make[Nothing, Int](PromiseId("dp-4"))
        for {
          log    <- ZIO.service[DurableLog]
          config <- ZIO.service[Configuration]
          env     = ZEnvironment(log, ExecutionEnvironment(Serializer.json, Deserializer.json, config))
          before <- promise.poll.provideEnvironment(env)
          _      <- promise.succeed(100).provideEnvironment(env)
          after  <- promise.poll.provideEnvironment(env)
        } yield assertTrue(before == None) && assertTrue(after == Some(Right(100)))
      },
      test("Can fail, poll failure") {
        val promise = DurablePromise.make[String, Int](PromiseId("dp-5"))
        for {
          log    <- ZIO.service[DurableLog]
          config <- ZIO.service[Configuration]
          env     = ZEnvironment(log, ExecutionEnvironment(Serializer.json, Deserializer.json, config))
          before <- promise.poll.provideEnvironment(env)
          _      <- promise.fail("not good").provideEnvironment(env)
          after  <- promise.poll.provideEnvironment(env)
        } yield assertTrue(before == None) && assertTrue(after == Some(Left("not good")))
      }
    ).provide(
      DurableLog.layer,
      IndexedStore.inMemory,
      Configuration.inMemory
    )
}
