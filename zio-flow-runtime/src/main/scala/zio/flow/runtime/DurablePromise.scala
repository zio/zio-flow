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

package zio.flow.runtime

import zio._
import zio.flow._
import IndexedStore.Index
import zio.flow.runtime.internal.Topics
import zio.schema._

final case class DurablePromise[E, A](promiseId: PromiseId) {

  def awaitEither(implicit
    schemaE: Schema[E],
    schemaA: Schema[A]
  ): ZIO[DurableLog with ExecutionEnvironment, ExecutorError, Either[E, A]] =
    ZIO.service[ExecutionEnvironment].flatMap { execEnv =>
      ZIO.log(s"Waiting for durable promise $promiseId") *>
        DurableLog
          .subscribe(Topics.promise(promiseId), Index(0L))
          .runHead
          .mapError(ExecutorError.LogError)
          .flatMap {
            case Some(data) =>
              ZIO.log(s"Got durable promise result for $promiseId") *>
                ZIO
                  .fromEither(execEnv.deserializer.deserialize[Either[E, A]](data))
                  .mapError(msg => ExecutorError.DeserializationError(s"awaited promise [$promiseId]", msg))
            case None =>
              ZIO.fail(ExecutorError.MissingPromiseResult(promiseId, "awaitEither"))
          }
    }

  def fail(
    error: E
  )(implicit
    schemaE: Schema[E],
    schemaA: Schema[A]
  ): ZIO[DurableLog with ExecutionEnvironment, ExecutorError, Boolean] =
    ZIO.service[ExecutionEnvironment].flatMap { execEnv =>
      ZIO.log(s"Setting $promiseId to failure $error") *>
        DurableLog
          .append(Topics.promise(promiseId), execEnv.serializer.serialize[Either[E, A]](Left(error)))
          .mapBoth(ExecutorError.LogError, _ == 0L)
    }

  def succeed(
    value: A
  )(implicit
    schemaE: Schema[E],
    schemaA: Schema[A]
  ): ZIO[DurableLog with ExecutionEnvironment, ExecutorError, Boolean] =
    ZIO.service[ExecutionEnvironment].flatMap { execEnv =>
      ZIO.log(s"Setting $promiseId to success: $value") *>
        DurableLog
          .append(Topics.promise(promiseId), execEnv.serializer.serialize[Either[E, A]](Right(value)))
          .mapBoth(ExecutorError.LogError, _ == 0L)
    }

  def poll(implicit
    schemaE: Schema[E],
    schemaA: Schema[A]
  ): ZIO[DurableLog with ExecutionEnvironment, ExecutorError, Option[Either[E, A]]] =
    ZIO.service[ExecutionEnvironment].flatMap { execEnv =>
      ZIO.log(s"Polling durable promise $promiseId") *>
        DurableLog
          .getAllAvailable(Topics.promise(promiseId), Index(0L))
          .runHead
          .mapError(ExecutorError.LogError)
          .flatMap(optData =>
            ZIO.foreach(optData)(data =>
              ZIO.log(s"Got durable promise result for $promiseId") *>
                ZIO
                  .fromEither(execEnv.deserializer.deserialize[Either[E, A]](data))
                  .mapError(msg => ExecutorError.DeserializationError(s"polled promise [$promiseId]", msg))
            )
          )
    }
}

object DurablePromise {
  implicit def schema[E, A]: Schema[DurablePromise[E, A]] = DeriveSchema.gen

  def make[E, A](promiseId: PromiseId): DurablePromise[E, A] =
    DurablePromise(promiseId)
}
