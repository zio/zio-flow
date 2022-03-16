//package zio.flow.utils
//
//import zio.ZIO
//import zio.flow._
//import zio.test.Assertion.{equalTo, isRight}
//import zio.test.{TestResult, assertM}
//
//object RemoteAssertionSyntax {
//
//  implicit final class RemoteAssertionOps[A](private val self: Remote[A]) extends AnyVal {
//    def <->(that: A): ZIO[RemoteContext, Nothing, TestResult] =
//      assertM(self.eval)(isRight(equalTo(that)))
//  }
//}
