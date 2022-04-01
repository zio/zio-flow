package zio.flow.remote

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow._
import zio.test.{Spec, TestEnvironment, TestFailure, TestSuccess}

object RemoteListSpec extends RemoteSpecBase {
  val suite1: Spec[TestEnvironment, TestFailure[Nothing], TestSuccess] =
    suite("RemoteListSpec")(
      test("Reverse") {
        val remoteList   = Remote(1 :: 2 :: 3 :: 4 :: Nil)
        val reversedList = remoteList.reverse
        reversedList <-> (4 :: 3 :: 2 :: 1 :: Nil)
      },
      test("Reverse empty list") {
        val reversedEmptyList = Remote[List[Int]](Nil).reverse
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
        val l2       = Remote[List[Int]](Nil)
        val appended = l1 ++ l2
        appended <-> (1 :: 2 :: 3 :: 4 :: 5 :: Nil)
      },
      test("Cons") {
        val l1   = Remote(1 :: 2 :: 3 :: Nil)
        val cons = Remote.Cons(l1, Remote(4))
        cons <-> (4 :: 1 :: 2 :: 3 :: Nil)
      },
      test("Fold") {
        val l1                = Remote(1 :: 2 :: 3 :: Nil)
        val fold: Remote[Int] = l1.fold(Remote(0))((a, b) => a + b)
        fold <-> 6
      }
    ).provideCustom(RemoteContext.inMemory)

  override def spec = suite("RemoteListSpec")(suite1)
}
