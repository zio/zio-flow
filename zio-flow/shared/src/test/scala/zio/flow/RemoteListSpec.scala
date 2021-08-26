package zio.flow

import zio.flow.Remote.Cons
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteListSpec extends DefaultRunnableSpec {
  val suite1: Spec[Annotations, TestFailure[Nothing], TestSuccess] = suite("RemoteListSpec")(
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
      val fold: Remote[Int] = l1.fold(Remote(0))((a, b) => a + b)
      fold <-> 6
    },
    test("IndexOf") {
      BoolAlgebra.all(
        // Remote(List.empty[Int]).indexOf(3) <-> -1,
        Remote(List(1, 2, 3)).indexOf(4) <-> -1,
        Remote(List(1, 2, 3)).indexOf(1) <-> -0,
        Remote(List(1, 2, 3, 4, 5)).indexOf(5, 0) <-> 4,
        Remote(List(1, 2, 3, 4, 5)).indexOf(5, 3) <-> 4,
        Remote(List(1, 2, 3, 4, 5)).indexOf(5, 4) <-> 4,
        Remote(List(1, 2, 3, 4, 5)).indexOf(5, 5) <-> -1
      )
    } @@ TestAspect.ignore, // TODO: remove ignore when UnCons#toTupleSchema is implemented
    test("LastIndexOf") {
      BoolAlgebra.all(
        // Remote(List.empty[Int]).lastIndexOf(3) <-> -1,
        Remote(List(1, 2, 3)).lastIndexOf(4) <-> -1,
        Remote(List(1, 2, 3)).lastIndexOf(1) <-> 0,
        Remote(List(1, 2, 3, 2, 1)).lastIndexOf(1) <-> 4,
        Remote(List(5, 4, 5, 4, 3)).lastIndexOf(5) <-> 2
      )
    } @@ TestAspect.ignore,
    test("StartsWith") {
      Remote(List(1, 2, 3)).startsWith(Remote(List.empty[Int])) <-> true
    } @@ TestAspect.ignore // TODO: remove ignore when UnCons#toTupleSchema is implemented
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("RemoteListSpec")(suite1)
}
