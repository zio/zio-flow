package zio.flow

import zio.test.Assertion._
import zio.test.{ DefaultRunnableSpec, Spec, TestFailure, TestSuccess, ZSpec, assert }

object RemoteBooleanSpec extends DefaultRunnableSpec {
  val suite1: Spec[Any, TestFailure[Nothing], TestSuccess]                   = suite("RemoteBooleanSpec")(
    test("And") {
      assert((Remote(true) && Remote(false)).eval)(equalTo(Right(false)))
      assert((Remote(true) && Remote(true)).eval)(equalTo(Right(true)))
      assert((Remote(false) && Remote(false)).eval)(equalTo(Right(false)))
    },
    test("Or") {
      assert((Remote(true) || Remote(false)).eval)(equalTo(Right(true)))
      assert((Remote(true) || Remote(true)).eval)(equalTo(Right(true)))
      assert((Remote(false) || Remote(false)).eval)(equalTo(Right(false)))
    },
    test("Not") {
      assert((!Remote(true)).eval)(equalTo(Right(false)))
      assert((!Remote(false)).eval)(equalTo(Right(true)))
    }
  )
  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("Remote Boolean Spec")(suite1)
}
