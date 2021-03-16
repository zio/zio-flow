package zio.flow

import zio.test.Assertion.equalTo
import zio.test.{ DefaultRunnableSpec, ZSpec, _ }

object NumericSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("NumericSpec")(test("Test addition of Remote") {
      val first  = Remote(1)
      val second = Remote(2)
      val result = (first + second).eval
      assert(result)(equalTo(Right(3)))
    })
}
