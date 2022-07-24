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
      testOp[R, A]("Sin", gen)(_.sin)(ops.sin),
      testOp[R, A]("Cos", gen)(_.cos)(ops.cos) @@ TestAspect.ignore,
      testOp[R, A]("Tan", gen)(_.tan)(ops.tan) @@ TestAspect.ignore,
      testOp[R, A]("Tan-Inverse", gen)(_.atan)(ops.inverseTan)
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
    inverseTan: A => A
  )

  private object Operations {
    val floatOperations: FractionalOps[Float] =
      FractionalOps[Float](
        sin = x => Math.sin(x.toDouble).toFloat,
        cos = x => Math.cos(x.toDouble).toFloat,
        tan = x => Math.tan(x.toDouble).toFloat,
        inverseTan = x => Math.atan(x.toDouble).toFloat
      )

    val doubleOperations: FractionalOps[Double] =
      FractionalOps[Double](
        sin = x => Math.sin(x),
        cos = x => Math.cos(x),
        tan = x => Math.tan(x),
        inverseTan = x => Math.atan(x)
      )

    val bigDecimalOperations: FractionalOps[BigDecimal] =
      FractionalOps[BigDecimal](
        sin = x => Math.sin(x.toDouble),
        cos = x => Math.cos(x.toDouble),
        tan = x => Math.tan(x.toDouble),
        inverseTan = x => Math.atan(x.toDouble)
      )

  }
}
