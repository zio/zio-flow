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

import zio.constraintless.TypeList._
import zio.flow._
import zio.flow.runtime.{DurableLog, DurablePromise, ExecutorError, IndexedStore}
import zio.schema.codec.BinaryCodecs
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}
import zio.{Promise, ZEnvironment, ZIO, ZNothing}

object DurablePromiseSpec extends ZIOSpecDefault {
  private val codecs = BinaryCodecs.make[Either[ZNothing, Int] :: Either[String, Int] :: End]

  override def spec: Spec[TestEnvironment, Any] =
    suite("DurablePromise")(
      test("Can't fail, await success") {
        val promise = DurablePromise.make[Nothing, Int](PromiseId("dp-1"))
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log)
          result <- Promise.make[ExecutorError, Either[Nothing, Int]]
          fiber  <- promise.awaitEither(codecs).provideEnvironment(env).intoPromise(result).fork
          _      <- promise.succeed(100)(codecs).provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Right(100))
      },
      test("Can fail, await success") {
        val promise = DurablePromise.make[String, Int](PromiseId("dp-2"))
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log)
          result <- Promise.make[ExecutorError, Either[String, Int]]
          fiber  <- promise.awaitEither(codecs).provideEnvironment(env).intoPromise(result).fork
          _      <- promise.succeed(100)(codecs).provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Right(100))
      },
      test("Can fail, await failure") {
        val promise = DurablePromise.make[String, Int](PromiseId("dp-3"))
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log)
          result <- Promise.make[ExecutorError, Either[String, Int]]
          fiber  <- promise.awaitEither(codecs).provideEnvironment(env).intoPromise(result).fork
          _      <- promise.fail("not good")(codecs).provideEnvironment(env)
          _      <- fiber.join
          value  <- result.await
        } yield assertTrue(value == Left("not good"))
      },
      test("Can't fail, poll success") {
        val promise = DurablePromise.make[Nothing, Int](PromiseId("dp-4"))
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log)
          before <- promise.poll(codecs).provideEnvironment(env)
          _      <- promise.succeed(100)(codecs).provideEnvironment(env)
          after  <- promise.poll(codecs).provideEnvironment(env)
        } yield assertTrue(before == None) && assertTrue(after == Some(Right(100)))
      },
      test("Can fail, poll failure") {
        val promise = DurablePromise.make[String, Int](PromiseId("dp-5"))
        for {
          log    <- ZIO.service[DurableLog]
          env     = ZEnvironment(log)
          before <- promise.poll(codecs).provideEnvironment(env)
          _      <- promise.fail("not good")(codecs).provideEnvironment(env)
          after  <- promise.poll(codecs).provideEnvironment(env)
        } yield assertTrue(before == None) && assertTrue(after == Some(Left("not good")))
      }
    ).provide(
      DurableLog.layer,
      IndexedStore.inMemory
    )
}
