package zio.flow

import zio._
import zio.flow.server._
import zio.random.Random
import zio.test._

object DurableLogSpec extends DefaultRunnableSpec {

  val durableLog: ZLayer[Any, Nothing, Has[DurableLog] with Has[IndexedStore]] =
    IndexedStore.live >+> DurableLog.live

  val values: Gen[Random with Sized, Chunk[Chunk[Byte]]] =
    Gen.chunkOf(Gen.chunkOf(Gen.anyByte)).noShrink

  def spec: ZSpec[Environment, Failure] =
    suite("DurableLogSpec")(
      testM("sequential read write") {
        checkM(values) { in =>
          (for {
            _   <- ZIO.foreach(in)(DurableLog.append("partition", _))
            out <- DurableLog.subscribe("partition", 0L).take(in.length.toLong).runCollect
          } yield assertTrue(out == in)).provideCustomLayer(durableLog)
        }
      },
      testM("concurrent read write") {
        checkM(values) { in =>
          (for {
            writer <- ZIO.foreach(in)(DurableLog.append("partition", _)).fork
            reader <- DurableLog.subscribe("partition", 0L).take(in.length.toLong).runCollect.fork
            _      <- writer.join
            out    <- reader.join
          } yield assertTrue(out == in)).provideCustomLayer(durableLog)
        }
      }
    )
}
