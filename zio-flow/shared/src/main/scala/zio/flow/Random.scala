package zio.flow

import zio.{Chunk, ZNothing}

import java.util.UUID

object Random {
  def nextBoolean: ZFlow[Any, ZNothing, Boolean] =
    ZFlow.random.map(_ <= 0.5)

  def nextBytes(length: Remote[Int]): ZFlow[Any, ZNothing, Chunk[Byte]] =
    nextIntBetween(Byte.MinValue.toInt, Byte.MaxValue.toInt + 1)
      .map(_.toByte)
      .replicate(length)

  def nextDouble: ZFlow[Any, ZNothing, Double] =
    ZFlow.random

  def nextDoubleBetween(minInclusive: Remote[Double], maxExclusive: Remote[Double]): ZFlow[Any, ZNothing, Double] =
    nextDouble.map { value =>
      minInclusive + value * (maxExclusive - minInclusive)
    }

  def nextFloat: ZFlow[Any, ZNothing, Float] =
    nextDouble.map(_.toFloat)

  def nextFloatBetween(minInclusive: Remote[Float], maxExclusive: Remote[Float]): ZFlow[Any, ZNothing, Float] =
    nextDoubleBetween(minInclusive.toDouble, maxExclusive.toDouble).map(_.toFloat)

  def nextInt: ZFlow[Any, ZNothing, Int] =
    nextDoubleBetween(Int.MinValue.toDouble, Int.MaxValue.toDouble).map(_.toInt)

  def nextIntBetween(minInclusive: Remote[Int], maxExclusive: Remote[Int]): ZFlow[Any, ZNothing, Int] =
    nextDoubleBetween(minInclusive.toDouble, maxExclusive.toDouble).map(_.toInt)

  def nextIntBounded(n: Remote[Int]): ZFlow[Any, ZNothing, Int] =
    nextDoubleBetween(0.0, n.toDouble).map(_.toInt)

  def nextLong: ZFlow[Any, ZNothing, Long] =
    nextDoubleBetween(Long.MinValue.toDouble, Long.MaxValue.toDouble).map(_.toLong)

  def nextLongBetween(minInclusive: Remote[Long], maxExclusive: Remote[Long]): ZFlow[Any, ZNothing, Long] =
    nextDoubleBetween(minInclusive.toDouble, maxExclusive.toDouble).map(_.toLong)

  def nextLongBounded(n: Remote[Long]): ZFlow[Any, ZNothing, Long] =
    nextDoubleBetween(0.0, n.toDouble).map(_.toLong)

  def nextPrintableChar: ZFlow[Any, ZNothing, Char] =
    nextDoubleBetween(33.toDouble, 17.toDouble).map(_.toChar)

  def nextString(length: Remote[Int]): ZFlow[Any, ZNothing, String] =
    nextPrintableChar.replicate(length).map(_.mkString)

  def nextUUID: ZFlow[Any, ZNothing, UUID] =
    ZFlow.randomUUID

  def shuffle[A](collection: Remote[List[A]]): ZFlow[Any, ZNothing, List[A]] =
    for {
      permutations <- ZFlow(collection.permutations)
      idx          <- nextIntBounded(permutations.size)
    } yield permutations(idx)
}
