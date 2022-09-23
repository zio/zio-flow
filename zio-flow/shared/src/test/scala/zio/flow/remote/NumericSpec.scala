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

import zio.ZLayer
import zio.flow._
import zio.flow.remote.numeric._
import zio.flow.utils.RemoteAssertionSyntax._
import zio.schema.Schema
import zio.test.{Gen, Spec, TestConfig, TestSuccess, check}

object NumericSpec extends RemoteSpecBase {

  override def spec =
    suite("NumericSpec")(
      numericTests("Int", Gen.int)(Operations.intOperations),
      numericTests("Long", Gen.long)(Operations.longOperations),
      numericTests("Short", Gen.short)(Operations.shortOperations),
      numericTests("Float", Gen.float)(Operations.floatOperations),
      numericTests("Double", Gen.double)(Operations.doubleOperations),
      numericTests("BigInt", Gen.bigInt(BigInt(Int.MinValue), BigInt(Int.MaxValue)))(Operations.bigIntOperations),
      numericTests(
        "BigDecimal",
        Gen.bigDecimal(BigDecimal(Double.MinValue), BigDecimal(Double.MaxValue))
      )(
        Operations.bigDecimalOperations
      )
    ).provideCustom(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

  private def numericTests[R, A: Schema: remote.numeric.Numeric](name: String, gen: Gen[R, A])(
    ops: NumericOps[A]
  ): Spec[R with TestConfig with RemoteContext with LocalContext, TestSuccess] =
    suite(name)(
      testOp[R, A]("Addition", gen, gen)(_ + _)(ops.addition),
      testOp[R, A]("Subtraction", gen, gen)(_ - _)(ops.subtraction),
      testOp[R, A]("Multiplication", gen, gen)(_ * _)(ops.multiplication),
      testOp[R, A]("Division", gen, gen.filterNot(ops.isZero))(_ / _)(ops.division),
      testOp[R, A]("Absolute", gen)(_.abs)(ops.abs),
      testOp[R, A]("Minimum", gen, gen)(_ min _)(ops.min),
      testOp[R, A]("Maximum", gen, gen)(_ max _)(ops.max)
    )

  private def testOp[R, A: Schema: Numeric](name: String, genX: Gen[R, A], genY: Gen[R, A])(
    numericOp: (Remote[A], Remote[A]) => Remote[A]
  )(op: (A, A) => A): Spec[R with TestConfig with RemoteContext with LocalContext, Nothing] =
    test(name) {
      check(genX, genY) { case (x, y) =>
        numericOp(x, y) <-> op(x, y)
      }
    }

  private def testOp[R, A: Schema: Numeric](name: String, gen: Gen[R, A])(
    numericOp: Remote[A] => Remote[A]
  )(op: A => A): Spec[R with TestConfig with RemoteContext with LocalContext, Nothing] =
    test(name) {
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
    max: (A, A) => A
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
        max = Math.max
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
        abs = x => x.abs,
        min = (x, y) => if (x < y) x else y,
        max = (x, y) => if (x > y) x else y
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
        abs = x => x.abs,
        min = (x, y) => if (x < y) x else y,
        max = (x, y) => if (x > y) x else y
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
        max = Math.max
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
        abs = x => Math.abs(x.toDouble).toShort,
        min = (x, y) => Math.min(x.toDouble, y.toDouble).toShort,
        max = (x, y) => Math.max(x.toDouble, y.toDouble).toShort
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
      max = Math.max
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
      max = Math.max
    )
  }
}
