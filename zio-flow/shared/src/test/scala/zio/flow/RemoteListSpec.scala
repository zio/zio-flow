package zio.flow

import zio.flow.Remote.Cons
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteListSpec extends DefaultRunnableSpec {
  val suite1: Spec[Any, TestFailure[Nothing], TestSuccess] = suite("RemoteListSpec")(
    test("Reverse") {
      val remoteList   = Remote(1 :: 2 :: 3 :: 4 :: Nil)
      val reversedList = remoteList.reverse
      reversedList <-> (4 :: 3 :: 2 :: 1 :: Nil)
    },
    test("Reverse empty list") {
      val reversedEmptyList = Remote(Nil).reverse
      reversedEmptyList <-> Nil
    },
    test("Append") {
      val l1       = Remote(1 :: 2 :: 3 :: 4 :: Nil)
      val l2       = Remote(5 :: 6 :: 7 :: Nil)
      val appended = l1 ++ l2
      appended <-> (1 :: 2 :: 3 :: 4 :: 5 :: 6 :: 7 :: Nil)
    },
    test("Append empty list") {
      val l1       = Remote(1 :: 2 :: 3 :: 4 :: 5 :: Nil)
      val l2       = Remote(Nil)
      val appended = l1 ++ l2
      appended <-> (1 :: 2 :: 3 :: 4 :: 5 :: Nil)
    },
    test("Cons") {
      val l1   = Remote(1 :: 2 :: 3 :: Nil)
      val cons = Cons(l1, Remote(4))
      cons <-> (4 :: 1 :: 2 :: 3 :: Nil)
    },
    test("Fold") {
      val l1                = Remote(1 :: 2 :: 3 :: Nil)
      val fold: Remote[Int] = l1.fold(Remote(0))((a,b) => a + b)
      fold <-> 6
    }
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("RemoteListSpec")(suite1)
}
