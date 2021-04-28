package zio.flow

import zio.test.Assertion.{ equalTo, isRight }
import zio.test._

object ListSpec extends DefaultRunnableSpec {
  val suite1: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("ListSpec")(
    test("Reverse") {
      val remoteList   = Remote(1 :: 2 :: 3 :: 4 :: Nil)
      val reversedList = remoteList.reverse
      assert(reversedList.eval)(equalTo(Right(4 :: 3 :: 2 :: 1 :: Nil)))
    },
    test("Reverse empty list") {
      val reversedEmptyList = Remote(Nil).reverse
      assert(reversedEmptyList.eval)(isRight) &&
      assert(reversedEmptyList.eval)(equalTo(Right(Nil)))
    },
    test("Append") {
      val l1       = Remote(1 :: 2 :: 3 :: 4 :: Nil)
      val l2       = Remote(5 :: 6 :: 7 :: Nil)
      val appended = l1 ++ l2
      assert(appended.eval)(equalTo(Right(1 :: 2 :: 3 :: 4 :: 5 :: 6 :: 7 :: Nil)))
    },
    test("Append empty list") {
      val l1       = Remote(1 :: 2 :: 3 :: 4 :: 5 :: Nil)
      val l2       = Remote(Nil)
      val appended = l1 ++ l2
      assert(appended.eval)(equalTo(l1.eval))
    }
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("ListSpec")(suite1)
}
