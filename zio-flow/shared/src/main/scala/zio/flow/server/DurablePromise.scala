package zio.flow.server

import java.io.IOException

import zio._
import zio.schema._

final case class DurablePromise[E, A](promiseId: String, durableLog: DurableLog) {
  def succeed(value: A)(implicit schemaA: Schema[A]): IO[IOException, Boolean] = ???

  def fail(error: E)(implicit schemaE: Schema[E]): IO[IOException, Boolean] = ???

  def awaitEither(implicit schemaE: Schema[E], schemaA: Schema[A]): IO[IOException, Either[E, A]] = ???
}
object DurablePromise                                                            {
  def make[E, A](promiseId: String, durableLog: DurableLog): DurablePromise[E, A] =
    DurablePromise(promiseId, durableLog)
}
