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
