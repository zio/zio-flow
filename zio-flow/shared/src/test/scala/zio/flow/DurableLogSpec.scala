package zio.flow

import zio._
import zio.flow.internal._
import zio.test._

object DurableLogSpec extends ZIOSpecDefault {

  val durableLog: ZLayer[Any, Nothing, DurableLog with IndexedStore] =
    IndexedStore.inMemory >+> DurableLog.live

  val values: Gen[Random with Sized, Chunk[Chunk[Byte]]] =
    Gen.chunkOf(Gen.chunkOf(Gen.byte)).noShrink

  def spec =
    suite("DurableLogSpec")(
      test("sequential read write") {
        check(values) { in =>
          (for {
            _   <- ZIO.foreach(in)(DurableLog.append("partition", _))
            out <- DurableLog.subscribe("partition", 0L).take(in.length.toLong).runCollect
          } yield assertTrue(out == in)).provideCustomLayer(durableLog)
        }
      },
      test("concurrent read write") {
        check(values) { in =>
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
