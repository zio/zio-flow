package zio.flow

import zio.test.Assertion.equalTo
import zio.test.{ DefaultRunnableSpec, ZSpec, _ }

object NumericSpec extends DefaultRunnableSpec {

  private def addition[T](first: T, second: T, expected: T)(implicit numericT: Numeric[T], schemaT: Schema[T]) =
    test("Test Addition") {
      assert((Remote(first) + Remote(second)).eval)(equalTo(Right(expected)))
    }

  private def subtraction[T](first: T, second: T, expected: T)(implicit numericT: Numeric[T], schemaT: Schema[T]) =
    test("Test Subtraction") {
      assert((Remote(first) - Remote(second)).eval)(equalTo(Right(expected)))
    }

  private def multiplication[T](first: T, second: T, expected: T)(implicit numericT: Numeric[T], schemaT: Schema[T]) =
    test("Test Multiplication") {
      assert((Remote(first) * Remote(second)).eval)(equalTo(Right(expected)))
    }

  private def division[T](first: T, second: T, expected: T)(implicit numericT: Numeric[T], schemaT: Schema[T]) =
    test("Test Division") {
      assert((Remote(first) / Remote(second)).eval)(equalTo(Right(expected)))
    }

  private def log[T](first: T, second: T, expected: T)(implicit numericT: Numeric[T], schemaT: Schema[T]) =
    test("Test Log") {
      assert((Remote(first) log Remote(second)).eval)(equalTo(Right(expected)))
    }

  private def root[T](first: T, second: T, expected: T)(implicit numericT: Numeric[T], schemaT: Schema[T]) =
    test("Test Log") {
      assert((Remote(first) root Remote(second)).eval)(equalTo(Right(expected)))
    }

  val suite1: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("NumericIntSpec")(
    addition(1, 2, 1 + 2),
    subtraction(1, 2, -1),
    multiplication(1, 2, 2),
    division(1, 2, 0),
    log(1, 2, Math.log(1) / Math.log(2)),
    root(1L, 2L, 1L)
  )

  val suite2: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("NumericLongSpec")(
    addition(1L, 2L, 1L + 2L),
    subtraction(1L, 2L, -1L),
    multiplication(1L, 2L, 2L),
    division(1L, 2L, 0L),
    log(1L, 2L, Math.log(1L) / Math.log(2L)),
    root(1L, 2L, 1L)
  )

  val suite3: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("NumericShortSpec")(
    addition(1.toShort, 2.toShort, 1.toShort + 2.toShort),
    subtraction(1.toShort, 2.toShort, 1.toShort - 2.toShort),
    multiplication(1.toShort, 2.toShort, 1.toShort * 2.toShort),
    division(1.toShort, 2.toShort, 1.toShort / 2.toShort),
    log(1.toShort, 2.toShort, Math.log(1.toShort) / Math.log(2.toShort)),
    root(1.toShort, 2.toShort, 1.toShort)
  )

  val suite4: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("NumericFloatSpec")(
    addition(1.555f, 2.333f, 1.555f + 2.333f),
    subtraction(1.555f, 2.333f, 1.555f - 2.333f),
    multiplication(1.555f, 2.333f, 1.555f * 2.333f),
    division(1.555f, 2.333f, 1.555f / 2.333f),
    log(1.555f, 2.333f, Math.log(1.555f) / Math.log(2.333f)),
    root(1.555f, 2.333f, 1.2083198f)
  )

  val suite5: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("NumericDoubleSpec")(
    addition(1.332435, 2.232123, 1.332435 + 2.232123),
    subtraction(1.555657, 2.333665, 1.555657 - 2.333665),
    multiplication(1.555657, 2.333665, 1.555657 * 2.333665),
    division(1.555657, 2.333665, 1.555657 / 2.333665),
    log(1.555657, 2.333665, Math.log(1.555657) / Math.log(2.333665)),
    root(1.555657, 2.333665, 1.2084734191706394)
  )

  val suite6: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("NumericBigIntSpec")(
    addition(BigInt("11111111111"), BigInt("22222222222"), BigInt("11111111111") + BigInt("22222222222")),
    subtraction(BigInt("11111111111"), BigInt("22222222222"), BigInt("11111111111") - BigInt("22222222222")),
    multiplication(BigInt("11111111111"), BigInt("22222222222"), BigInt("11111111111") * BigInt("22222222222")),
    division(BigInt("11111111111"), BigInt("22222222222"), BigInt("11111111111") / BigInt("22222222222")),
    log(
      BigInt("11111111111"),
      BigInt("22222222222"),
      BigInt((Math.log(BigInt("11111111111").doubleValue) / Math.log(BigInt("22222222222").doubleValue)).toInt)
    ),
    root(BigInt("111111111111111"), BigInt("222222222222222"), BigInt("1"))
  )

  val suite7: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("NumericBigDecimalSpec")(
    addition(
      BigDecimal("111111.111111"),
      BigDecimal("222222.222222"),
      BigDecimal("111111.111111") + BigDecimal("222222.222222")
    ),
    subtraction(
      BigDecimal("111111.111111"),
      BigDecimal("222222.222222"),
      BigDecimal("111111.111111") - BigDecimal("222222.222222")
    ),
    multiplication(
      BigDecimal("111111.111111"),
      BigDecimal("222222.222222"),
      BigDecimal("111111.111111") * BigDecimal("222222.222222")
    ),
    division(
      BigDecimal("111111.111111"),
      BigDecimal("222222.222222"),
      BigDecimal("111111.111111") / BigDecimal("222222.222222")
    ),
    log(
      BigDecimal("111111.111111"),
      BigDecimal("222222.222222"),
      BigDecimal(Math.log(BigDecimal("111111.111111").doubleValue) / Math.log(BigDecimal("222222.222222").doubleValue))
    ),
    root(BigDecimal("11111.1111111111"), BigDecimal("22222.2222222222"), BigDecimal("1.0004192944192845"))
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("NumericSpec")(suite1, suite2, suite3, suite4, suite5, suite6)
}
