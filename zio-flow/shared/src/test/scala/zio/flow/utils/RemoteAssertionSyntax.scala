package zio.flow.utils

import zio.flow._
import zio.test.Assertion.{equalTo, isRight}
import zio.test.{TestResult, assert}

object RemoteAssertionSyntax {

  implicit final class RemoteAssertionOps[A](private val self: Remote[A]) extends AnyVal {
    def <->(that: A): TestResult =
      assert(self.eval)(isRight(equalTo(that)))
  }
}
