package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.random.Random
import zio.test._

object NumericSpec extends DefaultRunnableSpec {

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("NumericSpec")(
      numericTests("Int", Gen.anyInt)(Operations.intOperations),
      numericTests("Long", Gen.anyLong)(Operations.longOperations),
      numericTests("Short", Gen.anyShort)(Operations.shortOperations),
      numericTests("Float", Gen.anyFloat)(Operations.floatOperations),
      numericTests("Double", Gen.anyDouble)(Operations.doubleOperations),
      numericTests("BigInt", Gen.bigInt(BigInt(Int.MinValue), BigInt(Int.MaxValue)))(Operations.bigIntOperations),
      numericTestsWithoutLogOrRoot(
        "BigDecimal",
        Gen.bigDecimal(BigDecimal(Double.MinValue), BigDecimal(Double.MaxValue))
      )(
        Operations.bigDecimalOperations
      )
    )

  private def numericTests[R, A: Schema: Numeric](name: String, gen: Gen[R, A])(
    ops: NumericOps[A]
  ): Spec[R with TestConfig, TestFailure[Nothing], TestSuccess] =
    suite(name)(
      testOp[R, A]("Addition", gen, gen)(_ + _)(ops.addition),
      testOp[R, A]("Subtraction", gen, gen)(_ - _)(ops.subtraction),
      testOp[R, A]("Multiplication", gen, gen)(_ * _)(ops.multiplication),
      testOp[R, A]("Division", gen, gen.filterNot(ops.isZero))(_ / _)(ops.division),
      testOp[R, A]("Log", gen, gen)(_ log _)(ops.log),
      testOp[R, A]("Root", gen, gen)(_ root _)(ops.root)
    )

  // TODO: BigDecimal fails Log/Root specs
  private def numericTestsWithoutLogOrRoot[R, A: Schema: Numeric](name: String, gen: Gen[R, A])(
    ops: NumericOps[A]
  ): Spec[R with TestConfig, TestFailure[Nothing], TestSuccess] =
    suite(name)(
      testOp[R, A]("Addition", gen, gen)(_ + _)(ops.addition),
      testOp[R, A]("Subtraction", gen, gen)(_ - _)(ops.subtraction),
      testOp[R, A]("Multiplication", gen, gen)(_ * _)(ops.multiplication),
      testOp[R, A]("Division", gen, gen.filterNot(ops.isZero))(_ / _)(ops.division)
//      testOp[R, A]("Log", gen, gen)(_ log _)(ops.log),
//      testOp[R, A]("Root", gen, gen)(_ root _)(ops.root)
    )

  private def testOp[R, A: Schema: Numeric](name: String, genX: Gen[R, A], genY: Gen[R, A])(
    numericOp: (Remote[A], Remote[A]) => Remote[A]
  )(op: (A, A) => A): ZSpec[R with TestConfig, Nothing] =
    testM(name) {
      check(genX, genY) { case (x, y) =>
        numericOp(x, y) <-> op(x, y)
      }
    }

  private case class NumericOps[A](
    addition: (A, A) => A,
    subtraction: (A, A) => A,
    multiplication: (A, A) => A,
    division: (A, A) => A,
    isZero: A => Boolean,
    log: (A, A) => A,
    root: (A, A) => A
  )

  private object Operations {
    val intOperations: NumericOps[Int] =
      NumericOps[Int](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        isZero = _ == 0,
        log = (x, y) => (Math.log(x.toDouble) / Math.log(y.toDouble)).toInt,
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toInt
      )

    val bigIntOperations: NumericOps[BigInt] =
      NumericOps[BigInt](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        isZero = _ == 0,
        log = (x, y) => (Math.log(x.doubleValue) / Math.log(y.doubleValue)).toInt,
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toInt
      )

    val bigDecimalOperations: NumericOps[BigDecimal] =
      NumericOps[BigDecimal](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        isZero = _ == 0,
        log = (x, y) => Math.log(x.doubleValue) / Math.log(y.doubleValue),
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble)
      )

    val longOperations: NumericOps[Long] =
      NumericOps[Long](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        isZero = _ == 0,
        log = (x, y) => (Math.log(x.toDouble) / Math.log(y.toDouble)).toLong,
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toLong
      )

    val shortOperations: NumericOps[Short] =
      NumericOps[Short](
        addition = (x, y) => (x + y).toShort,
        subtraction = (x, y) => (x - y).toShort,
        multiplication = (x, y) => (x * y).toShort,
        division = (x, y) => (x / y).toShort,
        isZero = _ == 0,
        log = (x, y) => (Math.log(x.toDouble) / Math.log(y.toDouble)).toShort,
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toShort
      )

    val doubleOperations: NumericOps[Double] = NumericOps[Double](
      addition = _ + _,
      subtraction = _ - _,
      multiplication = _ * _,
      division = _ / _,
      isZero = _ == 0,
      log = (x, y) => Math.log(x) / Math.log(y),
      root = (x, y) => Math.pow(x, 1 / y)
    )

    val floatOperations: NumericOps[Float] = NumericOps[Float](
      addition = _ + _,
      subtraction = _ - _,
      multiplication = _ * _,
      division = _ / _,
      isZero = _ == 0,
      log = (x, y) => (Math.log(x.toDouble) / Math.log(y.toDouble)).toFloat,
      root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toFloat
    )
  }
}
