/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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
import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow.utils.RemoteAssertionSyntax._
import zio.schema.Schema
import zio.test.{Gen, Spec, TestSuccess, check}

object NumericSpec extends RemoteSpecBase {

  override def spec =
    suite("NumericSpec")(
      numericTests("Int", Gen.int)(Operations.intOperations),
      numericTests("Long", Gen.long)(Operations.longOperations),
      numericTests("Short", Gen.short)(Operations.shortOperations),
      numericTests("Float", Gen.float)(Operations.floatOperations),
      numericTests("Double", Gen.double)(Operations.doubleOperations),
      numericTests("BigInt", Gen.bigInt(BigInt(Int.MinValue), BigInt(Int.MaxValue)))(Operations.bigIntOperations),
      numericTests("BigDecimal", Gen.bigDecimal(BigDecimal(Double.MinValue), BigDecimal(Double.MaxValue)))(
        Operations.bigDecimalOperations
      ),
      numericTests("Char", Gen.char)(Operations.charOperations)
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)

  private def numericTests[R, A: Schema: remote.numeric.Numeric](name: String, gen: Gen[R, A])(
    ops: NumericOps[A]
  ): Spec[R with RemoteContext with LocalContext, TestSuccess] =
    suite(name)(
      testOp[R, A]("+", gen, gen)(_ + _)(ops.addition),
      testOp[R, A]("-", gen, gen)(_ - _)(ops.subtraction),
      testOp[R, A]("*", gen, gen)(_ * _)(ops.multiplication),
      testOp[R, A]("/", gen, gen.filterNot(ops.isZero))(_ / _)(ops.division),
      testOp[R, A]("%", gen, gen.filterNot(ops.isZero))(_ % _)(ops.mod),
      testOp[R, A]("abs", gen)(_.abs)(ops.abs),
      testOp[R, A]("neg", gen)(-_)(ops.neg),
      testOp[R, A]("sign", gen)(_.sign)(ops.sign),
      testOp[R, A]("min", gen, gen)(_ min _)(ops.min),
      testOp[R, A]("max", gen, gen)(_ max _)(ops.max)
    )

  private def testOp[R, A: Schema: Numeric](name: String, genX: Gen[R, A], genY: Gen[R, A])(
    numericOp: (Remote[A], Remote[A]) => Remote[A]
  )(op: (A, A) => A): Spec[R with RemoteContext with LocalContext, Nothing] =
    test(name) {
      check(genX, genY) { case (x, y) =>
        numericOp(x, y) <-> op(x, y)
      }
    }

  private def testOp[R, A: Schema: Numeric](name: String, gen: Gen[R, A])(
    numericOp: Remote[A] => Remote[A]
  )(op: A => A): Spec[R with RemoteContext with LocalContext, Nothing] =
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
    mod: (A, A) => A,
    isZero: A => Boolean,
    abs: A => A,
    min: (A, A) => A,
    max: (A, A) => A,
    neg: A => A,
    sign: A => A
  )

  private object Operations {
    val intOperations: NumericOps[Int] =
      NumericOps[Int](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        mod = _ % _,
        isZero = _ == 0,
        abs = x => Math.abs(x),
        min = Math.min,
        max = Math.max,
        neg = x => -x,
        sign = x => Math.signum(x.toDouble).toInt
      )

    val bigIntOperations: NumericOps[BigInt] =
      NumericOps[BigInt](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        mod = _ % _,
        isZero = _ == 0,
        abs = x => x.abs,
        min = (x, y) => if (x < y) x else y,
        max = (x, y) => if (x > y) x else y,
        neg = x => -x,
        sign = x => BigInt(x.signum)
      )

    val bigDecimalOperations: NumericOps[BigDecimal] =
      NumericOps[BigDecimal](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        mod = _ % _,
        isZero = _ == 0,
        abs = x => x.abs,
        min = (x, y) => if (x < y) x else y,
        max = (x, y) => if (x > y) x else y,
        neg = x => -x,
        sign = x => BigDecimal(x.signum)
      )

    val longOperations: NumericOps[Long] =
      NumericOps[Long](
        addition = _ + _,
        subtraction = _ - _,
        multiplication = _ * _,
        division = _ / _,
        mod = _ % _,
        isZero = _ == 0,
        abs = x => Math.abs(x),
        min = Math.min,
        max = Math.max,
        neg = x => -x,
        sign = x => Math.signum(x.toDouble).toLong
      )

    val shortOperations: NumericOps[Short] =
      NumericOps[Short](
        addition = (x, y) => (x + y).toShort,
        subtraction = (x, y) => (x - y).toShort,
        multiplication = (x, y) => (x * y).toShort,
        division = (x, y) => (x / y).toShort,
        mod = (x, y) => (x % y).toShort,
        isZero = _ == 0,
        abs = x => Math.abs(x.toDouble).toShort,
        min = (x, y) => Math.min(x.toDouble, y.toDouble).toShort,
        max = (x, y) => Math.max(x.toDouble, y.toDouble).toShort,
        neg = x => (-x).toShort,
        sign = x => Math.signum(x.toDouble).toShort
      )

    val doubleOperations: NumericOps[Double] = NumericOps[Double](
      addition = _ + _,
      subtraction = _ - _,
      multiplication = _ * _,
      division = _ / _,
      mod = _ % _,
      isZero = _ == 0,
      abs = x => Math.abs(x),
      min = Math.min,
      max = Math.max,
      neg = x => -x,
      sign = x => Math.signum(x)
    )

    val floatOperations: NumericOps[Float] = NumericOps[Float](
      addition = _ + _,
      subtraction = _ - _,
      multiplication = _ * _,
      division = _ / _,
      mod = _ % _,
      isZero = _ == 0,
      abs = x => Math.abs(x),
      min = Math.min,
      max = Math.max,
      neg = x => -x,
      sign = x => Math.signum(x)
    )

    val charOperations: NumericOps[Char] = NumericOps[Char](
      addition = (a: Char, b: Char) => (a + b).toChar,
      subtraction = (a: Char, b: Char) => (a - b).toChar,
      multiplication = (a: Char, b: Char) => (a * b).toChar,
      division = (a: Char, b: Char) => (a / b).toChar,
      mod = (a: Char, b: Char) => (a % b).toChar,
      isZero = (a: Char) => a.toInt == 0,
      abs = x => Math.abs(x.toInt).toChar,
      min = (a: Char, b: Char) => Math.min(a.toInt, b.toInt).toChar,
      max = (a: Char, b: Char) => Math.max(a.toInt, b.toInt).toChar,
      neg = x => (-x).toChar,
      sign = x => Math.signum(x.toDouble).toChar
    )
  }
}
