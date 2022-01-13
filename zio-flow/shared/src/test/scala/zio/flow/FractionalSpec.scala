package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.schema.Schema
import zio.test._

object FractionalSpec extends ZIOSpecDefault {

  override def spec =
    suite("FractionalSpec")(
      fractionalTests("Float", Gen.float)(Operations.floatOperations),
      fractionalTests("Double", Gen.double)(Operations.doubleOperations),
      fractionalTests("BigDecimal", Gen.bigDecimal(BigDecimal(Double.MinValue), BigDecimal(Double.MaxValue)))(
        Operations.bigDecimalOperations
      )
    )

  private def fractionalTests[R, A: Schema](name: String, gen: Gen[R, A])(ops: FractionalOps[A])(implicit
    fractionalA: remote.Fractional[A]
  ) =
    suite(name)(
      testOp[R, A]("Sin", gen)(_.sin)(ops.sin),
      testOp[R, A]("Cos", gen)(_.cos)(ops.cos) @@ TestAspect.ignore,
      testOp[R, A]("Tan", gen)(_.tan)(ops.tan) @@ TestAspect.ignore,
      testOp[R, A]("Tan-Inverse", gen)(_.tanInverse)(ops.inverseTan)
    )

  private def testOp[R, A: Schema: remote.Fractional](name: String, gen: Gen[R, A])(
    fractionalOp: Remote[A] => Remote[A]
  )(op: A => A): ZSpec[R with TestConfig, Nothing] =
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
