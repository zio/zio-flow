package zio.flow.utils

import zio.ZIO
import zio.flow._
import zio.schema.Schema
import zio.test.Assertion.{equalTo, succeeds}
import zio.test.{TestResult, assertZIO}

object RemoteAssertionSyntax {

  implicit final class RemoteAssertionOps[A: Schema](private val self: Remote[A]) {
    def <->[A1 <: A](that: A1): ZIO[RemoteContext with LocalContext, Nothing, TestResult] =
      assertZIO(self.eval[A].exit)(succeeds(equalTo(that)))
  }
}
