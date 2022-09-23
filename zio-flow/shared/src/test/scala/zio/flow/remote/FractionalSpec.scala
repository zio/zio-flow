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
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{LocalContext, Remote, RemoteContext, remote}
import zio.schema.Schema
import zio.test.{Gen, Spec, TestAspect, TestConfig, check}

object FractionalSpec extends RemoteSpecBase {

  override def spec =
    suite("FractionalSpec")(
      fractionalTests("Float", Gen.float)(Operations.floatOperations),
      fractionalTests("Double", Gen.double)(Operations.doubleOperations),
      fractionalTests("BigDecimal", Gen.bigDecimal(BigDecimal(Double.MinValue), BigDecimal(Double.MaxValue)))(
        Operations.bigDecimalOperations
      )
    ).provideCustom(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

  private def fractionalTests[R, A: Schema](name: String, gen: Gen[R, A])(ops: FractionalOps[A])(implicit
    fractionalA: remote.numeric.Fractional[A]
  ) =
    suite(name)(
      testOp[R, A]("Sin", gen)(math.sin)(ops.sin),
      testOp[R, A]("Cos", gen)(math.cos)(ops.cos),
      testOp[R, A]("Tan", gen)(math.tan)(ops.tan),
      testOp[R, A]("Tan-Inverse", gen)(math.atan)(ops.inverseTan),
      testOp[R, A]("Floor", gen)(math.floor)(ops.floor),
      testOp[R, A]("Ceil", gen)(math.ceil)(ops.ceil),
      testOp[R, A]("Round", gen)(_.round)(ops.round),
      testOp[R, A]("Log", gen)(math.log)(ops.log) @@ TestAspect.ignore,   // TODO: bigdecimal cannot handle NaN
      testOp[R, A]("Sqrt", gen)(math.sqrt)(ops.sqrt) @@ TestAspect.ignore // TODO: bigdecimal cannot handle NaN
    )

  private def testOp[R, A: Schema: remote.numeric.Fractional](name: String, gen: Gen[R, A])(
    fractionalOp: Remote[A] => Remote[A]
  )(op: A => A): Spec[R with TestConfig with RemoteContext with LocalContext, Nothing] =
    test(name) {
      check(gen) { x =>
        fractionalOp(x) <-> op(x)
      }
    }

  private case class FractionalOps[A](
    sin: A => A,
    cos: A => A,
    tan: A => A,
    inverseTan: A => A,
    floor: A => A,
    ceil: A => A,
    round: A => A,
    log: A => A,
    sqrt: A => A
  )

  private object Operations {
    val floatOperations: FractionalOps[Float] =
      FractionalOps[Float](
        sin = x => Math.sin(x.toDouble).toFloat,
        cos = x => Math.cos(x.toDouble).toFloat,
        tan = x => Math.tan(x.toDouble).toFloat,
        inverseTan = x => Math.atan(x.toDouble).toFloat,
        floor = x => Math.floor(x.toDouble).toFloat,
        ceil = x => Math.ceil(x.toDouble).toFloat,
        round = x => Math.round(x.toDouble).toFloat,
        log = x => Math.log(x.toDouble).toFloat,
        sqrt = x => Math.sqrt(x.toDouble).toFloat
      )

    val doubleOperations: FractionalOps[Double] =
      FractionalOps[Double](
        sin = x => Math.sin(x),
        cos = x => Math.cos(x),
        tan = x => Math.tan(x),
        inverseTan = x => Math.atan(x),
        floor = x => Math.floor(x),
        ceil = x => Math.ceil(x),
        round = x => Math.round(x).toDouble,
        log = x => Math.log(x),
        sqrt = x => Math.sqrt(x)
      )

    val bigDecimalOperations: FractionalOps[BigDecimal] =
      FractionalOps[BigDecimal](
        sin = x => Math.sin(x.toDouble),
        cos = x => Math.cos(x.toDouble),
        tan = x => Math.tan(x.toDouble),
        inverseTan = x => Math.atan(x.toDouble),
        floor = x => BigDecimal(Math.floor(x.toDouble)),
        ceil = x => BigDecimal(Math.ceil(x.toDouble)),
        round = x => BigDecimal(Math.round(x.toDouble)),
        log = x => BigDecimal(Math.log(x.toDouble)),
        sqrt = x => BigDecimal(Math.sqrt(x.toDouble))
      )

  }
}
