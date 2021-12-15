/*
 * Copyright 2021 John A. De Goes and the ZIO Contributors
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

package zio.flow.server

import java.io.IOException

import zio._
import zio.console.putStrLn
import zio.schema._

final case class DurablePromise[E, A](promiseId: String, durableLog: DurableLog, promise: Promise[E, A]) {
  def succeed(value: A)(implicit schemaA: Schema[A]): IO[IOException, Boolean] =
    putStrLn("Succeed on Promise " + promise).provide(Has(console.Console.Service.live)) *> promise.succeed(value)

  def fail(error: E)(implicit schemaE: Schema[E]): IO[IOException, Boolean] = promise.fail(error)

  def awaitEither(implicit schemaE: Schema[E], schemaA: Schema[A]): IO[IOException, Either[E, A]] =
    putStrLn("Await on Promise " + promise).provide(Has(console.Console.Service.live)) *> promise.await.either
}

object DurablePromise {
  def make[E, A](promiseId: String, durableLog: DurableLog, promise: Promise[E, A]): DurablePromise[E, A] =
    DurablePromise(promiseId, durableLog, promise)
}
