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

package zio.flow.runtime

import zio._
import zio.flow._
import IndexedStore.Index
import zio.constraintless.{IsElementOf, TypeList}
import zio.flow.runtime.internal.Topics
import zio.schema._
import zio.schema.codec.BinaryCodecs

final case class DurablePromise[E, A](promiseId: PromiseId) {

  def awaitEither[Types <: TypeList](codecs: BinaryCodecs[Types])(implicit
    ev: Either[E, A] IsElementOf Types
  ): ZIO[DurableLog, ExecutorError, Either[E, A]] =
    DurableLog
      .subscribe(Topics.promise(promiseId), Index(0L))
      .runHead
      .mapError(ExecutorError.LogError)
      .flatMap {
        case Some(data) =>
          ZIO
            .fromEither(codecs.decode[Either[E, A]](data))
            .mapError(msg => ExecutorError.DeserializationError(s"awaited promise [$promiseId]", msg.message))
        case None =>
          ZIO.fail(ExecutorError.MissingPromiseResult(promiseId, "awaitEither"))
      }

  def fail[Types <: TypeList](
    error: E
  )(codecs: BinaryCodecs[Types])(implicit
    ev: Either[E, A] IsElementOf Types
  ): ZIO[DurableLog, ExecutorError, Boolean] =
    DurableLog
      .append(Topics.promise(promiseId), codecs.encode[Either[E, A]](Left(error)))
      .mapBoth(ExecutorError.LogError, _ == 0L)

  def succeed[Types <: TypeList](
    value: A
  )(codecs: BinaryCodecs[Types])(implicit
    ev: Either[E, A] IsElementOf Types
  ): ZIO[DurableLog, ExecutorError, Boolean] =
    DurableLog
      .append(Topics.promise(promiseId), codecs.encode[Either[E, A]](Right(value)))
      .mapBoth(ExecutorError.LogError, _ == 0L)

  def poll[Types <: TypeList](codecs: BinaryCodecs[Types])(implicit
    ev: Either[E, A] IsElementOf Types
  ): ZIO[DurableLog, ExecutorError, Option[Either[E, A]]] =
    DurableLog
      .getAllAvailable(Topics.promise(promiseId), Index(0L))
      .runHead
      .mapError(ExecutorError.LogError)
      .flatMap(optData =>
        ZIO.foreach(optData)(data =>
          ZIO
            .fromEither(codecs.decode[Either[E, A]](data))
            .mapError(msg => ExecutorError.DeserializationError(s"polled promise [$promiseId]", msg.message))
        )
      )

  def delete(): ZIO[DurableLog, ExecutorError, Unit] =
    DurableLog
      .delete(Topics.promise(promiseId))
      .mapError(ExecutorError.LogError)
}

object DurablePromise {
  private val typeId = TypeId.parse("zio.flow.runtime.DurablePromise")

  implicit def schema[E, A]: Schema[DurablePromise[E, A]] =
    schemaAny.asInstanceOf[Schema[DurablePromise[E, A]]]

  implicit lazy val schemaAny: Schema[DurablePromise[Any, Any]] =
    Schema.CaseClass1[PromiseId, DurablePromise[Any, Any]](
      typeId,
      Schema.Field("promiseId", Schema[PromiseId], get0 = _.promiseId, set0 = (a, b) => a.copy(promiseId = b)),
      DurablePromise.apply
    )

  def make[E, A](promiseId: PromiseId): DurablePromise[E, A] =
    DurablePromise(promiseId)
}
