package zio.flow.test

import zio.{Chunk, NonEmptyChunk}
import zio.test.{Gen, Sized}

object Generators {
  val namespace: Gen[Sized, String] =
    Gen.alphaNumericStringBounded(
      min = 3,
      max = 255
    )

  def newTimeBasedName(): String =
    s"${java.time.Instant.now}"
      .replaceAll(":", "_")
      .replaceAll(".", "_")

  val nonEmptyByteChunkGen: Gen[Sized, NonEmptyChunk[Byte]] =
    Gen.chunkOf1(Gen.byte)

  val byteChunkGen: Gen[Sized, Chunk[Byte]] =
    Gen.chunkOf(Gen.byte)
}
