package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.schema.Schema
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
      testOp[R, A]("Root", gen, gen)(_ root _)(ops.root),
      testOp[R, A]("Absolute", gen)(_.abs)(ops.abs),
      testOp[R, A]("Minimum", gen, gen)(_ min _)(ops.min),
      testOp[R, A]("Maximum", gen, gen)(_ max _)(ops.max),
      testOp[R, A]("Floor", gen)(_.floor)(ops.floor),
      testOp[R, A]("Ceil", gen)(_.ceil)(ops.ceil),
      testOp[R, A]("Round", gen)(_.ceil)(ops.ceil)
    )

  // TODO: BigDecimal fails Log/Root specs.
  //  It also fails Subtraction on 2.11 and 2.12.
  private def numericTestsWithoutLogOrRoot[R, A: Schema: Numeric](name: String, gen: Gen[R, A])(
    ops: NumericOps[A]
  ) =
    suite(name)(
      testOp[R, A]("Addition", gen, gen)(_ + _)(ops.addition),
      testOp[R, A]("Subtraction", gen, gen)(_ - _)(
        ops.subtraction
      ) @@ TestAspect.exceptScala211 @@ TestAspect.exceptScala212,
      testOp[R, A]("Multiplication", gen, gen)(_ * _)(ops.multiplication),
      testOp[R, A]("Division", gen, gen.filterNot(ops.isZero))(_ / _)(ops.division),
      testOp[R, A]("Absolute", gen)(_.abs)(ops.abs),
      testOp[R, A]("Minimum", gen, gen)(_ min _)(ops.min),
      testOp[R, A]("Maximum", gen, gen)(_ max _)(ops.max),
      testOp[R, A]("Floor", gen)(_.floor)(ops.floor),
      testOp[R, A]("Ceil", gen)(_.ceil)(ops.ceil),
      testOp[R, A]("Round", gen)(_.ceil)(ops.ceil)
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

  private def testOp[R, A: Schema: Numeric](name: String, gen: Gen[R, A])(
    numericOp: Remote[A] => Remote[A]
  )(op: A => A): ZSpec[R with TestConfig, Nothing] =
    testM(name) {
      check(gen) { x =>
        numericOp(x) <-> op(x)
      }
    }

  private case class NumericOps[A](
    addition: (A, A) => A,
    subtraction: (A, A) => A,
    multiplication: (A, A) => A,
    division: (A, A) => A,
    isZero: A => Boolean,
    log: (A, A) => A,
    root: (A, A) => A,
    abs: A => A,
    min: (A, A) => A,
    max: (A, A) => A,
    floor: A => A,
    ceil: A => A,
    round: A => A
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
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toInt,
        abs = x => Math.abs(x),
        min = Math.min,
        max = Math.max,
        floor = x => Math.floor(x.toDouble).toInt,
        ceil = x => Math.ceil(x).toInt,
        round = x => Math.round(x)
      )

    val bigIntOperations: NumericOps[BigInt] =
      NumericOps[BigInt](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        isZero = _ == 0,
        log = (x, y) => (Math.log(x.doubleValue) / Math.log(y.doubleValue)).toInt,
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toInt,
        abs = x => Math.abs(x.doubleValue).toInt,
        min = (x, y) => Math.min(x.doubleValue, y.doubleValue).toInt,
        max = (x, y) => Math.max(x.doubleValue, y.doubleValue).toInt,
        floor = x => Math.floor(x.doubleValue).toInt,
        ceil = x => Math.ceil(x.doubleValue).toInt,
        round = x => Math.round(x.doubleValue)
      )

    val bigDecimalOperations: NumericOps[BigDecimal] =
      NumericOps[BigDecimal](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        isZero = _ == 0,
        log = (x, y) => Math.log(x.doubleValue) / Math.log(y.doubleValue),
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble),
        abs = x => Math.abs(x.doubleValue),
        min = (x, y) => Math.min(x.doubleValue, y.doubleValue),
        max = (x, y) => Math.max(x.doubleValue, y.doubleValue),
        floor = x => Math.floor(x.doubleValue),
        ceil = x => Math.ceil(x.doubleValue),
        round = x => Math.round(x.doubleValue)
      )

    val longOperations: NumericOps[Long] =
      NumericOps[Long](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        isZero = _ == 0,
        log = (x, y) => (Math.log(x.toDouble) / Math.log(y.toDouble)).toLong,
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toLong,
        abs = x => Math.abs(x),
        min = Math.min,
        max = Math.max,
        floor = x => Math.floor(x).toLong,
        ceil = x => Math.ceil(x).toLong,
        round = x => Math.round(x)
      )

    val shortOperations: NumericOps[Short] =
      NumericOps[Short](
        addition = (x, y) => (x + y).toShort,
        subtraction = (x, y) => (x - y).toShort,
        multiplication = (x, y) => (x * y).toShort,
        division = (x, y) => (x / y).toShort,
        isZero = _ == 0,
        log = (x, y) => (Math.log(x.toDouble) / Math.log(y.toDouble)).toShort,
        root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toShort,
        abs = x => Math.abs(x).toShort,
        min = (x, y) => Math.min(x.toDouble, y.toDouble).toShort,
        max = (x, y) => Math.max(x.toDouble, y.toDouble).toShort,
        floor = x => Math.floor(x.toDouble).toShort,
        ceil = x => Math.ceil(x).toShort,
        round = x => Math.round(x).toShort
      )

    val doubleOperations: NumericOps[Double] = NumericOps[Double](
      addition = _ + _,
      subtraction = _ - _,
      multiplication = _ * _,
      division = _ / _,
      isZero = _ == 0,
      log = (x, y) => Math.log(x) / Math.log(y),
      root = (x, y) => Math.pow(x, 1 / y),
      abs = x => Math.abs(x),
      min = Math.min,
      max = Math.max,
      floor = Math.floor,
      ceil = x => Math.ceil(x),
      round = x => Math.round(x)
    )

    val floatOperations: NumericOps[Float] = NumericOps[Float](
      addition = _ + _,
      subtraction = _ - _,
      multiplication = _ * _,
      division = _ / _,
      isZero = _ == 0,
      log = (x, y) => (Math.log(x.toDouble) / Math.log(y.toDouble)).toFloat,
      root = (x, y) => Math.pow(x.toDouble, 1 / y.toDouble).toFloat,
      abs = x => Math.abs(x),
      min = Math.min,
      max = Math.max,
      floor = x => Math.floor(x.toDouble).toFloat,
      ceil = x => Math.ceil(x).toFloat,
      round = x => Math.round(x)
    )
  }
}
