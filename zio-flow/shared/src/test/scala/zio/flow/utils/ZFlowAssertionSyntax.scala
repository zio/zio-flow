package zio.flow.utils

import java.io.IOException
import java.net.URI

import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.flow.server.{DurableLog, KeyValueStore, PersistentExecutor}
import zio.flow.utils.MocksForGCExample.mockInMemoryForGCExample
import zio.flow.zFlow.ZFlow
import zio.flow.zFlow.ZFlowExecutor.InMemory
import zio.flow.{Activity, ActivityError, Operation, OperationExecutor}
import zio.schema.DeriveSchema.gen
import zio.schema.Schema
import zio.stream.ZStream
import zio.{Chunk, Has, IO, Ref, ZIO, console}

object ZFlowAssertionSyntax {

  object Mocks {
    val mockActivity: Activity[Any, Int] =
      Activity(
        "Test Activity",
        "Mock activity created for test",
        Operation.Http[Any, Int](
          new URI("testUrlForActivity.com"),
          "GET",
          Map.empty[String, String],
          Schema.fail("No schema"),
          implicitly[Schema[Int]]
        ),
        ZFlow.succeed(12),
        ZFlow.succeed(15)
      )

    object mockOpExec extends OperationExecutor[Console with Clock] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Console with Clock, ActivityError, A] =
        console.putStrLn("Activity processing") *> ZIO.succeed(input.asInstanceOf[A])
    }

    val mockInMemoryTestClock: ZIO[Clock with Console, Nothing, InMemory[String, Clock with Console]] = ZIO
      .environment[Clock with Console]
      .flatMap(testClock =>
        Ref
          .make[Map[String, Ref[InMemory.State]]](Map.empty)
          .map(ref => InMemory[String, Clock with Console](testClock, mockOpExec, ref))
      )

    val mockInMemoryLiveClock: ZIO[Any, Nothing, InMemory[String, Has[Clock.Service] with Has[Console.Service]]] =
      Ref
        .make[Map[String, Ref[InMemory.State]]](Map.empty)
        .map(ref =>
          InMemory(Has(zio.clock.Clock.Service.live) ++ Has(zio.console.Console.Service.live), mockOpExec, ref)
        )

    val doesNothingDurableLog: DurableLog = new DurableLog {
      override def append(topic: String, value: Chunk[Byte]): IO[IOException, Long] =
        putStrLn("Append Does Nothing").provide(Has(console.Console.Service.live)).as(12L)

      override def subscribe(topic: String, position: Long): ZStream[Any, IOException, Chunk[Byte]] =
        ZStream.fromChunk(Chunk.empty)
    }

    val doesNothingKVStore: KeyValueStore = new KeyValueStore {
      override def put(namespace: String, key: Chunk[Byte], value: Chunk[Byte]): IO[IOException, Boolean] =
        ZIO.succeed(true)

      override def get(namespace: String, key: Chunk[Byte]): IO[IOException, Option[Chunk[Byte]]] = ZIO.none

      override def scanAll(namespace: String): ZStream[Any, IOException, (Chunk[Byte], Chunk[Byte])] =
        ZStream.fromChunk(Chunk.empty) zip ZStream.fromChunk(Chunk.empty)
    }

    val mockPersistentLiveClock: ZIO[Any, Nothing, PersistentExecutor] = Ref
      .make[Map[String, Ref[PersistentExecutor.State[_, _]]]](Map.empty)
      .map(ref =>
        PersistentExecutor(
          Clock.Service.live,
          doesNothingDurableLog,
          doesNothingKVStore,
          mockOpExec.asInstanceOf[OperationExecutor[Any]],
          ref
        )
      )
  }

  import Mocks._

  implicit final class InMemoryZFlowAssertion[R, E, A](private val zflow: ZFlow[Any, E, A]) {

    def evaluateTestInMem(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryTestClock
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateLiveInMem(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Clock with Console, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryLiveClock
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateInMemForGCExample(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Any, E, A] = {
      val compileResult = for {
        inMemory <- mockInMemoryForGCExample
        result   <- inMemory.submit("1234", zflow)
      } yield result
      compileResult
    }

    def evaluateLivePersistent(implicit schemaA: Schema[A], schemaE: Schema[E]): ZIO[Any, E, A] = for {
      persistentEval <- mockPersistentLiveClock
      result         <- persistentEval.submit("1234", zflow)
    } yield result
  }
}
