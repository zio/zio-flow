/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.remote

import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{LocalContext, Remote, RemoteContext, remote}
import zio.schema.Schema
import zio.test.Assertion.{equalTo, hasField}
import zio.test.{Gen, Spec, TestEnvironment, check}
import zio.{Scope, ZLayer}

import scala.util.{Failure, Success, Try}

object FractionalSpec extends RemoteSpecBase {

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("FractionalSpec")(
      fractionalTests("Float", Gen.float, Gen.double(-1.0, 1.0).map(_.toFloat))(Operations.floatOperations),
      fractionalTests("Double", Gen.double, Gen.double(-1.0, 1.0))(Operations.doubleOperations),
      fractionalTests(
        "BigDecimal",
        Gen.bigDecimal(BigDecimal(Double.MinValue), BigDecimal(Double.MaxValue)),
        Gen.bigDecimal(BigDecimal(-1.0), BigDecimal(1.0))
      )(
        Operations.bigDecimalOperations
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)

  private def fractionalTests[R, A: Schema](name: String, gen: Gen[R, A], gen11: Gen[R, A])(ops: FractionalOps[A])(
    implicit fractionalA: remote.numeric.Fractional[A]
  ) =
    suite(name)(
      testOp[R, A]("sin", gen)(math.sin)(ops.sin),
      testOp[R, A]("cos", gen)(math.cos)(ops.cos),
      testOp[R, A]("tan", gen)(math.tan)(ops.tan),
      testOp[R, A]("asin", gen11)(math.asin)(ops.asin),
      testOp[R, A]("acos", gen11)(math.acos)(ops.acos),
      testOp[R, A]("atan", gen11)(math.atan)(ops.atan),
      testOp[R, A]("pow", gen, gen)(math.pow)(ops.pow),
      testOp[R, A]("log", gen)(math.log)(ops.log),
      testOp[R, A]("floor", gen)(math.floor)(ops.floor),
      testOp[R, A]("ceil", gen)(math.ceil)(ops.ceil),
      testOp[R, A]("round", gen)(math.round)(ops.round),
      testOp[R, A]("toRadians", gen)(math.toRadians)(ops.toRadians),
      testOp[R, A]("toDegrees", gen)(math.toDegrees)(ops.toDegrees),
      testOp[R, A]("rint", gen)(math.rint)(ops.rint),
      testOp[R, A]("nextUp", gen)(math.nextUp)(ops.nextUp),
      testOp[R, A]("nextDown", gen)(math.nextDown)(ops.nextDown),
      testOp[R, A]("scalb", gen, gen)(math.scalb)(ops.scalb),
      testOp[R, A]("sqrt", gen)(math.sqrt)(ops.sqrt),
      testOp[R, A]("cbrt", gen)(math.cbrt)(ops.cbrt),
      testOp[R, A]("exp", gen)(math.exp)(ops.exp),
      testOp[R, A]("expm1", gen)(math.expm1)(ops.expm1),
      testOp[R, A]("log1p", gen)(math.log1p)(ops.log1p),
      testOp[R, A]("log10", gen)(math.log10)(ops.log10),
      testOp[R, A]("sinh", gen)(math.sinh)(ops.sinh),
      testOp[R, A]("cosh", gen)(math.cosh)(ops.cosh),
      testOp[R, A]("tanh", gen)(math.tanh)(ops.tanh),
      testOp[R, A]("ulp", gen)(math.ulp)(ops.ulp),
      testOp[R, A]("atan2", gen, gen)(math.atan2)(ops.atan2),
      testOp[R, A]("hypot", gen, gen)(math.hypot)(ops.hypot),
      testOp[R, A]("copySign", gen, gen)(math.copySign)(ops.copySign),
      testOp[R, A]("nextAfter", gen, gen)(math.nextAfter)(ops.nextAfter),
      testOp[R, A]("ieeeRemainder", gen, gen)(math.IEEEremainder)(ops.ieeeRemainder)
    )

  private def testOp[R, A: Schema: remote.numeric.Fractional](name: String, gen: Gen[R, A])(
    fractionalOp: Remote[A] => Remote[A]
  )(op: A => A): Spec[R with RemoteContext with LocalContext, Nothing] =
    test(name) {
      check(gen) { x =>
        Try(op(x)) match {
          case Failure(exception) =>
            fractionalOp(x) failsWith hasField(
              "message",
              _.toMessage,
              equalTo(s"Remote evaluation failed: ${exception.getMessage}")
            )
          case Success(value) => fractionalOp(x) <-> value
        }
      }
    }

  private def testOp[R, A: Schema: remote.numeric.Fractional](name: String, genX: Gen[R, A], genY: Gen[R, A])(
    fractionalOp: (Remote[A], Remote[A]) => Remote[A]
  )(op: (A, A) => A): Spec[R with RemoteContext with LocalContext, Nothing] =
    test(name) {
      check(genX, genY) { case (x, y) =>
        Try(op(x, y)) match {
          case Failure(exception) =>
            fractionalOp(x, y) failsWith hasField(
              "message",
              _.toMessage,
              equalTo(s"Remote evaluation failed: ${exception.getMessage}")
            )
          case Success(value) => fractionalOp(x, y) <-> value
        }
      }
    }

  private case class FractionalOps[A](
    sin: A => A,
    cos: A => A,
    tan: A => A,
    asin: A => A,
    acos: A => A,
    atan: A => A,
    pow: (A, A) => A,
    log: A => A,
    floor: A => A,
    ceil: A => A,
    round: A => A,
    toRadians: A => A,
    toDegrees: A => A,
    rint: A => A,
    nextUp: A => A,
    nextDown: A => A,
    scalb: (A, A) => A,
    sqrt: A => A,
    cbrt: A => A,
    exp: A => A,
    expm1: A => A,
    log1p: A => A,
    log10: A => A,
    sinh: A => A,
    cosh: A => A,
    tanh: A => A,
    ulp: A => A,
    atan2: (A, A) => A,
    hypot: (A, A) => A,
    copySign: (A, A) => A,
    nextAfter: (A, A) => A,
    ieeeRemainder: (A, A) => A
  )

  private object Operations {
    val floatOperations: FractionalOps[Float] =
      FractionalOps[Float](
        sin = value => scala.math.sin(value.toDouble).toFloat,
        cos = value => scala.math.cos(value.toDouble).toFloat,
        tan = value => scala.math.tan(value.toDouble).toFloat,
        asin = value => scala.math.asin(value.toDouble).toFloat,
        acos = value => scala.math.acos(value.toDouble).toFloat,
        atan = value => scala.math.atan(value.toDouble).toFloat,
        pow = (a, b) => scala.math.pow(a.toDouble, b.toDouble).toFloat,
        log = value => scala.math.log(value.toDouble).toFloat,
        floor = value => scala.math.floor(value.toDouble).toFloat,
        ceil = value => scala.math.ceil(value.toDouble).toFloat,
        round = value => scala.math.round(value).toFloat,
        toRadians = value => scala.math.toRadians(value.toDouble).toFloat,
        toDegrees = value => scala.math.toDegrees(value.toDouble).toFloat,
        rint = value => scala.math.rint(value.toDouble).toFloat,
        nextUp = Math.nextUp,
        nextDown = Math.nextDown,
        scalb = (a, b) => Math.scalb(a, b.toInt),
        sqrt = value => scala.math.sqrt(value.toDouble).toFloat,
        cbrt = value => scala.math.cbrt(value.toDouble).toFloat,
        exp = value => scala.math.exp(value.toDouble).toFloat,
        expm1 = value => scala.math.expm1(value.toDouble).toFloat,
        log1p = value => scala.math.log1p(value.toDouble).toFloat,
        log10 = value => scala.math.log10(value.toDouble).toFloat,
        sinh = value => scala.math.sinh(value.toDouble).toFloat,
        cosh = value => scala.math.cosh(value.toDouble).toFloat,
        tanh = value => scala.math.tanh(value.toDouble).toFloat,
        ulp = scala.math.ulp,
        atan2 = (a, b) => scala.math.atan2(a.toDouble, b.toDouble).toFloat,
        hypot = (a, b) => scala.math.hypot(a.toDouble, b.toDouble).toFloat,
        copySign = Math.copySign,
        nextAfter = (a, b) => Math.nextAfter(a, b.toDouble),
        ieeeRemainder = (a, b) => scala.math.IEEEremainder(a.toDouble, b.toDouble).toFloat
      )

    val doubleOperations: FractionalOps[Double] =
      FractionalOps[Double](
        sin = scala.math.sin,
        cos = scala.math.cos,
        tan = scala.math.tan,
        asin = scala.math.asin,
        acos = scala.math.acos,
        atan = scala.math.atan,
        pow = scala.math.pow,
        log = scala.math.log,
        floor = scala.math.floor,
        ceil = scala.math.ceil,
        round = value => scala.math.round(value).toDouble,
        toRadians = scala.math.toRadians,
        toDegrees = scala.math.toDegrees,
        rint = scala.math.rint,
        nextUp = Math.nextUp,
        nextDown = Math.nextDown,
        scalb = (a, b) => Math.scalb(a, b.toInt),
        sqrt = scala.math.sqrt,
        cbrt = scala.math.cbrt,
        exp = scala.math.exp,
        expm1 = scala.math.expm1,
        log1p = scala.math.log1p,
        log10 = scala.math.log10,
        sinh = scala.math.sinh,
        cosh = scala.math.cosh,
        tanh = scala.math.tanh,
        ulp = scala.math.ulp,
        atan2 = scala.math.atan2,
        hypot = scala.math.hypot,
        copySign = Math.copySign,
        nextAfter = Math.nextAfter,
        ieeeRemainder = scala.math.IEEEremainder
      )

    val bigDecimalOperations: FractionalOps[BigDecimal] =
      FractionalOps[BigDecimal](
        sin = value => BigDecimal(scala.math.sin(value.doubleValue)),
        cos = value => BigDecimal(scala.math.cos(value.doubleValue)),
        tan = value => BigDecimal(scala.math.tan(value.doubleValue)),
        asin = value => BigDecimal(scala.math.asin(value.doubleValue)),
        acos = value => BigDecimal(scala.math.acos(value.doubleValue)),
        atan = value => BigDecimal(scala.math.atan(value.doubleValue)),
        pow = (a, b) => BigDecimal(scala.math.pow(a.doubleValue, b.doubleValue)),
        log = value => BigDecimal(scala.math.log(value.doubleValue)),
        floor = value => BigDecimal(scala.math.floor(value.doubleValue)),
        ceil = value => BigDecimal(scala.math.ceil(value.doubleValue)),
        round = value => value.rounded,
        toRadians = value => value * Math.toRadians(1.0),
        toDegrees = value => value * Math.toDegrees(1.0),
        rint = value => BigDecimal(scala.math.rint(value.doubleValue)),
        nextUp = value => BigDecimal(Math.nextUp(value.doubleValue)),
        nextDown = value => BigDecimal(Math.nextDown(value.doubleValue)),
        scalb = (a, b) => BigDecimal(Math.scalb(a.doubleValue, b.toInt)),
        sqrt = value => BigDecimal(scala.math.sqrt(value.doubleValue)),
        cbrt = value => BigDecimal(scala.math.cbrt(value.doubleValue)),
        exp = value => BigDecimal(scala.math.exp(value.doubleValue)),
        expm1 = value => BigDecimal(scala.math.expm1(value.doubleValue)),
        log1p = value => BigDecimal(scala.math.log1p(value.doubleValue)),
        log10 = value => BigDecimal(scala.math.log10(value.doubleValue)),
        sinh = value => BigDecimal(scala.math.sinh(value.doubleValue)),
        cosh = value => BigDecimal(scala.math.cosh(value.doubleValue)),
        tanh = value => BigDecimal(scala.math.tanh(value.doubleValue)),
        ulp = value => BigDecimal(scala.math.ulp(value.doubleValue)),
        atan2 = (a, b) => BigDecimal(scala.math.atan2(a.doubleValue, b.doubleValue)),
        hypot = (a, b) => BigDecimal(scala.math.hypot(a.doubleValue, b.doubleValue)),
        copySign = (a, b) => BigDecimal(Math.copySign(a.doubleValue, b.doubleValue)),
        nextAfter = (a, b) => BigDecimal(Math.nextAfter(a.doubleValue, b.doubleValue)),
        ieeeRemainder = (a, b) => BigDecimal(scala.math.IEEEremainder(a.doubleValue, b.doubleValue))
      )

  }
}
