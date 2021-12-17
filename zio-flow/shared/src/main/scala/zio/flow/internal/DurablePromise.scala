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

package zio.flow.internal

import java.io._

import zio._
import zio.schema._

final case class DurablePromise[E, A](promiseId: String) {

  def awaitEither(implicit schemaE: Schema[E], schemaA: Schema[A]): ZIO[Has[DurableLog], IOException, Either[E, A]] =
    DurableLog.subscribe(topic(promiseId), 0L).runHead.map(value => deserialize[E, A](value.get))

  def fail(error: E)(implicit schemaE: Schema[E]): ZIO[Has[DurableLog], IOException, Boolean] =
    DurableLog.append(topic(promiseId), serialize(Left(error))).map(_ == 0L)

  def succeed(value: A)(implicit schemaA: Schema[A]): ZIO[Has[DurableLog], IOException, Boolean] =
    DurableLog.append(topic(promiseId), serialize(Right(value))).map(_ == 0L)

  private def deserialize[E, A](value: Chunk[Byte])(implicit schemaE: Schema[E], schemaA: Schema[A]): Either[E, A] = {
    val ios = new ObjectInputStream(new ByteArrayInputStream(value.toArray))
    ios.readObject().asInstanceOf[Either[E, A]]
  }

  private def serialize[A](a: A)(implicit schema: Schema[A]): Chunk[Byte] = {
    val bf  = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(bf)
    oos.writeObject(a)
    oos.close()
    Chunk.fromArray(bf.toByteArray)
  }

  private def topic(promiseId: String): String =
    s"_zflow_durable_promise_$promiseId"
}

object DurablePromise {

  def make[E, A](promiseId: String): DurablePromise[E, A] =
    DurablePromise(promiseId)
}
