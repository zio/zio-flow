package zio.flow.utils

import zio._
import zio.flow.{Activity, ActivityError, Operation, OperationExecutor, ZFlow}
import zio.flow.internal.{DurableLog, KeyValueStore}
import zio.schema.Schema
import zio.stream.ZStream

import java.io.IOException
import java.net.URI

object MockHelpers {
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
      Console.printLine("Activity processing") *> ZIO.succeed(input.asInstanceOf[A])
  }

  val doesNothingDurableLog: DurableLog = new DurableLog {
    override def append(topic: String, value: Chunk[Byte]): IO[IOException, Long] =
      Console.printLine("Append Does Nothing").provideLayer(Console.live).as(12L)

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
}
