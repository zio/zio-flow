/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.remote

import zio.{Scope, ZLayer}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow._
import zio.test.{Spec, TestEnvironment}

object RemoteListSyntaxSpec extends RemoteSpecBase {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RemoteListSyntax")(
      remoteTest("Remote.nil")(
        Remote.nil[Int] <-> List.empty[Int]
      ),
      remoteTest("Remote.list")(
        Remote.list(1, 2, 3) <-> List(1, 2, 3)
      ),
      remoteTest("++")(
        Remote.list(1, 2, 3, 4) ++ Remote.list(5, 6, 7) <-> List(1, 2, 3, 4, 5, 6, 7),
        Remote.list(1, 2, 3, 4) ++ Remote.nil[Int] <-> List(1, 2, 3, 4),
        Remote.nil[Int] ++ Remote.list(1, 2, 3, 4) <-> List(1, 2, 3, 4),
        Remote.nil[Int] ++ Remote.nil[Int] <-> List.empty
      ),
      remoteTest("++:")(
        Remote.list(1, 2, 3, 4) ++: Remote.list(5, 6, 7) <-> List(1, 2, 3, 4, 5, 6, 7),
        Remote.list(1, 2, 3, 4) ++: Remote.nil[Int] <-> List(1, 2, 3, 4),
        Remote.nil[Int] ++: Remote.list(1, 2, 3, 4) <-> List(1, 2, 3, 4),
        Remote.nil[Int] ++: Remote.nil[Int] <-> List.empty
      ),
      remoteTest("+:")(
        Remote(1) +: Remote.nil[Int] <-> List(1),
        Remote(1) +: Remote.list(2, 3) <-> List(1, 2, 3)
      ),
      remoteTest(":+")(
        Remote.nil[Int] :+ Remote(1) <-> List(1),
        Remote.list(2, 3) :+ Remote(1) <-> List(2, 3, 1)
      ),
      remoteTest(":++")(
        Remote.list(1, 2, 3, 4) :++ Remote.list(5, 6, 7) <-> List(1, 2, 3, 4, 5, 6, 7),
        Remote.list(1, 2, 3, 4) :++ Remote.nil[Int] <-> List(1, 2, 3, 4),
        Remote.nil[Int] :++ Remote.list(1, 2, 3, 4) <-> List(1, 2, 3, 4),
        Remote.nil[Int] :++ Remote.nil[Int] <-> List.empty
      ),
      remoteTest("::")(
        Remote(1) :: Remote.nil[Int] <-> List(1),
        Remote(1) :: Remote.list(2, 3) <-> List(1, 2, 3)
      ),
      remoteTest(":::")(
        Remote.list(1, 2, 3, 4) ::: Remote.list(5, 6, 7) <-> List(1, 2, 3, 4, 5, 6, 7),
        Remote.list(1, 2, 3, 4) ::: Remote.nil[Int] <-> List(1, 2, 3, 4),
        Remote.nil[Int] ::: Remote.list(1, 2, 3, 4) <-> List(1, 2, 3, 4),
        Remote.nil[Int] ::: Remote.nil[Int] <-> List.empty
      ),
      remoteTest("appended")(
        Remote.nil[Int] :+ Remote(1) <-> List(1),
        Remote.list(2, 3) :+ Remote(1) <-> List(2, 3, 1)
      ),
      remoteTest("appendedAll")(
        Remote.list(1, 2, 3, 4).appendedAll(Remote.list(5, 6, 7)) <-> List(1, 2, 3, 4, 5, 6, 7),
        Remote.list(1, 2, 3, 4).appendedAll(Remote.nil[Int]) <-> List(1, 2, 3, 4),
        Remote.nil[Int].appendedAll(Remote.list(1, 2, 3, 4)) <-> List(1, 2, 3, 4),
        Remote.nil[Int].appendedAll(Remote.nil[Int]) <-> List.empty
      ),
      remoteTest("apply")(
        Remote.nil[Int].apply(0) failsWithRemoteError ("get called on empty Option"),
        Remote.list(1, 2, 3).apply(1) <-> 2
      ),
      remoteTest("concat")(
        Remote.list(1, 2, 3, 4).concat(Remote.list(5, 6, 7)) <-> List(1, 2, 3, 4, 5, 6, 7),
        Remote.list(1, 2, 3, 4).concat(Remote.nil[Int]) <-> List(1, 2, 3, 4),
        Remote.nil[Int].concat(Remote.list(1, 2, 3, 4)) <-> List(1, 2, 3, 4),
        Remote.nil[Int].concat(Remote.nil[Int]) <-> List.empty
      ),
      remoteTest("contains")(
        Remote.nil[Int].contains(10) <-> false,
        Remote.list(1, 2, 3).contains(10) <-> false,
        Remote.list(9, 10, 11).contains(10) <-> true
      ),
      remoteTest("containsSlice")(
        Remote.nil[Int].containsSlice(Remote.list(1, 2)) <-> false,
        Remote.list(1, 2, 3, 4).containsSlice(Remote.list(2, 3)) <-> true,
        Remote.list(1, 2, 3).containsSlice(Remote.list(2, 3)) <-> true,
        Remote.list(1, 2).containsSlice(Remote.list(2, 3)) <-> false
      ),
      remoteTest("corresponds")(
        Remote
          .list(1, 2, 3)
          .corresponds(Remote.list("x", "xx", "xxx"))((n: Remote[Int], s: Remote[String]) => s.length === n) <-> true,
        Remote
          .list(1, 2, 1)
          .corresponds(Remote.list("x", "xx", "xxx"))((n: Remote[Int], s: Remote[String]) => s.length === n) <-> false
      ),
      remoteTest("count")(
        Remote.nil[Int].count(_ % 2 === 0) <-> 0,
        Remote.list(1, 2, 3, 4, 5).count(_ % 2 === 0) <-> 2
      ),
      remoteTest("diff")(
        Remote.list(1, 2, 3, 4).diff(Remote.list(2, 4)) <-> List(1, 3),
        Remote.list(1, 2, 3, 4).diff(Remote.nil[Int]) <-> List(1, 2, 3, 4),
        Remote.nil[Int].diff(Remote.list(1, 2)) <-> List.empty
      ),
      remoteTest("distinct")(
        Remote.nil[Int].distinct <-> List.empty[Int],
        Remote.list(1, 2, 3, 2, 1).distinct <-> List(1, 2, 3)
      ),
      remoteTest("distinctBy")(
        Remote.list("abc", "axy", "bca", "bb", "c").distinctBy(_.length) <-> List("abc", "bb", "c"),
        Remote.nil[String].distinctBy(_.length) <-> List.empty[String]
      ),
      remoteTest("drop")(
        Remote.nil[Int].drop(10) <-> List.empty[Int],
        Remote.list(1, 2, 3).drop(0) <-> List(1, 2, 3),
        Remote.list(1, 2, 3).drop(2) <-> List(3),
        Remote.list(1, 2, 3).drop(4) <-> List.empty[Int]
      ),
      remoteTest("dropWhile")(
        Remote.nil[Int].dropWhile(_ < 3) <-> List.empty[Int],
        Remote.list(1, 2, 3, 4).dropWhile(_ < 3) <-> List(3, 4)
      ),
      remoteTest("endsWith")(
        Remote.nil[Int].endsWith(Remote.list(1, 2)) <-> false,
        Remote.list(1, 2, 3).endsWith(Remote.list(1, 2)) <-> false,
        Remote.list(0, 0, 1, 2).endsWith(Remote.list(1, 2)) <-> true
      ),
      remoteTest("exists")(
        Remote.nil[Int].exists(_ % 2 === 0) <-> false,
        Remote.list(1, 3, 5, 7).exists(_ % 2 === 0) <-> false,
        Remote.list(1, 3, 4, 7).exists(_ % 2 === 0) <-> true
      ),
      remoteTest("filter")(
        Remote.nil[Int].filter(_ % 2 === 0) <-> List.empty[Int],
        Remote.list(1, 3, 5, 7).filter(_ % 2 === 0) <-> List.empty[Int],
        Remote.list(1, 3, 4, 7).filter(_ % 2 === 0) <-> List(4)
      ),
      remoteTest("filterNot")(
        Remote.nil[Int].filterNot(_ % 2 === 0) <-> List.empty[Int],
        Remote.list(1, 3, 5, 7).filterNot(_ % 2 === 0) <-> List(1, 3, 5, 7),
        Remote.list(1, 3, 4, 7).filterNot(_ % 2 === 0) <-> List(1, 3, 7)
      ),
      remoteTest("find")(
        Remote.nil[Int].find(_ === 3) <-> None,
        Remote.list(1, 2, 4, 5).find(_ === 3) <-> None,
        Remote.list(1, 2, 3, 4).find(_ === 3) <-> Some(3),
        Remote.list("aaa", "b", "ccc", "d").find(_.length === 3) <-> Some("aaa")
      ),
      remoteTest("findLast")(
        Remote.nil[String].findLast(_.length === 3) <-> None,
        Remote.list("aa", "bb", "cc").findLast(_.length === 3) <-> None,
        Remote.list("aaa", "b", "ccc", "d").findLast(_.length === 3) <-> Some("ccc")
      ),
      remoteTest("flatMap")(
        Remote.nil[Int].flatMap(_ => Remote.list(1, 2, 3)) <-> List.empty[Int],
        Remote.list(1, 2, 3).flatMap((n: Remote[Int]) => n :: Remote.list(2, 3)) <-> List(1, 2, 3, 2, 2, 3, 3, 2, 3),
        Remote.list(1, 2, 3).flatMap(_ => Remote.nil[Int]) <-> List.empty[Int]
      ),
      remoteTest("flatten")(
        Remote.nil[List[Int]].flatten <-> List.empty[Int],
        Remote.list(Remote.nil[Int], Remote.nil[Int]).flatten <-> List.empty[Int],
        Remote.list(Remote.list(1, 2), Remote.list(3, 4)).flatten <-> List(1, 2, 3, 4)
      ),
      remoteTest("foldLeft")(
        Remote.list(1.0, 2.0, 3.0).foldLeft(4.0)(_ / _) <-> List(1.0, 2.0, 3.0).foldLeft(4.0)(_ / _),
        Remote.nil[Double].foldLeft(4.0)(_ / _) <-> 4.0
      ),
      remoteTest("foldRight")(
        Remote.list(1.0, 2.0, 3.0).foldRight(4.0)(_ / _) <-> List(1.0, 2.0, 3.0).foldRight(4.0)(_ / _),
        Remote.nil[Double].foldRight(4.0)(_ / _) <-> 4.0
      ),
      remoteTest("forall")(
        Remote.nil[Int].forall(_ % 2 === 0) <-> true,
        Remote.list(1, 2, 3, 4).forall(_ % 2 === 0) <-> false,
        Remote.list(2, 4, 6, 8).forall(_ % 2 === 0) <-> true
      ),
      remoteTest("head")(
        Remote.nil[Int].head failsWithRemoteError "List is empty",
        Remote.list(1, 2, 3).head <-> 1
      ),
      remoteTest("headOption")(
        Remote.nil[Int].headOption <-> None,
        Remote.list(1, 2, 3).headOption <-> Some(1)
      ),
      remoteTest("indexOf")(
        Remote.nil[Int].indexOf(3) <-> -1,
        Remote.list(1, 2, 4, 5).indexOf(3) <-> -1,
        Remote.list(1, 2, 3, 4).indexOf(3) <-> 2,
        Remote.list(1, 2, 3, 4).indexOf(3, 2) <-> 2,
        Remote.list(1, 2, 3, 4, 4, 3).indexOf(3, 3) <-> 5
      ),
      remoteTest("indexOfSlice")(
        Remote.nil[Int].indexOfSlice(List(2, 3)) <-> -1,
        Remote.list(1, 2, 3, 4, 5, 2, 3, 6).indexOfSlice(List(2, 3)) <-> 1,
        Remote.list(1, 2, 3, 4, 5, 2, 3, 6).indexOfSlice(List(2, 3), 1) <-> 1,
        Remote.list(1, 2, 3, 4, 5, 2, 3, 6).indexOfSlice(List(2, 3), 3) <-> 5
      ),
      remoteTest("indexWhere")(
        Remote.nil[Int].indexWhere(_ % 2 === 0) <-> -1,
        Remote.list(1, 2, 3, 4, 5, 2, 3, 6).indexWhere(_ % 2 === 0) <-> 1,
        Remote.list(1, 2, 3, 4, 5, 2, 3, 6).indexWhere(_ % 2 === 0, 1) <-> 1,
        Remote.list(1, 2, 3, 4, 5, 2, 3, 6).indexWhere(_ % 2 === 0, 2) <-> 3
      ),
      remoteTest("init")(
        Remote.nil[Int].init failsWithRemoteError "List is empty",
        Remote.list(1).init <-> List.empty[Int],
        Remote.list(1, 2, 3).init <-> List(1, 2)
      ),
      remoteTest("inits")(
        Remote.nil[Int].inits <-> List(List.empty[Int]),
        Remote.list(1, 2, 3).inits <-> List(List(1, 2, 3), List(1, 2), List(1), List())
      ),
      remoteTest("intersect")(
        Remote.nil[Int].intersect(Remote.list(3, 4, 5, 6)) <-> List.empty[Int],
        Remote.list(1, 2, 3, 4).intersect(Remote.list(3, 4, 5, 6)) <-> List(3, 4),
        Remote.list(1, 2, 3, 4).intersect(Remote.nil[Int]) <-> List.empty[Int]
      ),
      remoteTest("isDefinedAt")(
        Remote.nil[Int].isDefinedAt(0) <-> false,
        Remote.list(1, 2, 3).isDefinedAt(0) <-> true,
        Remote.list(1, 2, 3).isDefinedAt(2) <-> true,
        Remote.list(1, 2, 3).isDefinedAt(3) <-> false
      ),
      remoteTest("isEmpty")(
        Remote.nil[Int].isEmpty <-> true,
        Remote.list(1, 2, 3).isEmpty <-> false
      ),
      remoteTest("last")(
        Remote.nil[Int].last failsWithRemoteError "List is empty",
        Remote.list(1, 2, 3).last <-> 3
      ),
      remoteTest("lastIndexOf")(
        Remote.nil[Int].lastIndexOf(3) <-> -1,
        Remote.list(1, 2, 4, 4).lastIndexOf(3) <-> -1,
        Remote.list(1, 2, 3, 4, 3, 4).lastIndexOf(3) <-> 4,
        Remote.list(1, 2, 3, 4, 3, 4).lastIndexOf(3, 4) <-> 4,
        Remote.list(1, 2, 3, 4, 3, 4).lastIndexOf(3, 3) <-> 2
      ),
      remoteTest("lastIndexOfSlice")(
        Remote.nil[Int].lastIndexOfSlice(Remote.list(3, 4)) <-> -1,
        Remote.list(1, 2, 4, 4).lastIndexOfSlice(Remote.list(3, 4)) <-> -1,
        Remote.list(1, 2, 3, 4, 3, 4).lastIndexOfSlice(Remote.list(3, 4)) <-> 4,
        Remote.list(1, 2, 3, 4, 3, 4).lastIndexOfSlice(Remote.list(3, 4), 5) <-> 4,
        Remote.list(1, 2, 3, 4, 3, 4).lastIndexOfSlice(Remote.list(3, 4), 3) <-> 2
      ),
      remoteTest("lastIndexWhere")(
        Remote.nil[Int].lastIndexWhere(_ % 2 === 0) <-> -1,
        Remote.list(1, 2, 3, 4, 5, 2, 3, 6).lastIndexWhere(_ % 2 === 0) <-> 7,
        Remote.list(1, 2, 3, 4, 5, 2, 3, 6).lastIndexWhere(_ % 2 === 0, 6) <-> 5
      ),
      remoteTest("lastOption")(
        Remote.nil[Int].lastOption <-> None,
        Remote.list(1, 2, 3).lastOption <-> Some(3)
      ),
      remoteTest("length")(
        Remote.nil[Int].length <-> 0,
        Remote.list(1, 2, 3).length <-> 3
      ),
      remoteTest("map")(
        Remote.nil[Int].map(_ * 2) <-> List.empty[Int],
        Remote.list(1, 2, 3).map(_ * 2) <-> List(2, 4, 6)
      ),
      remoteTest("max")(
        Remote.nil[Int].max failsWithRemoteError "List is empty",
        Remote.list(1, 6, 4, 3, 8, 0, 4).max <-> 8
      ),
      remoteTest("maxBy")(
        Remote.nil[String].maxBy(_.length) failsWithRemoteError "List is empty",
        Remote.list("aaa", "a", "abcd", "ab").maxBy(_.length) <-> "abcd"
      ),
      remoteTest("maxByOption")(
        Remote.nil[String].maxByOption(_.length) <-> None,
        Remote.list("aaa", "a", "abcd", "ab").maxByOption(_.length) <-> Some("abcd")
      ),
      remoteTest("maxOption")(
        Remote.nil[Int].maxOption <-> None,
        Remote.list(1, 6, 4, 3, 8, 0, 4).maxOption <-> Some(8)
      ),
      remoteTest("min")(
        Remote.nil[Int].min failsWithRemoteError "List is empty",
        Remote.list(1, 6, 4, 3, 8, 0, 4).min <-> 0
      ),
      remoteTest("minBy")(
        Remote.nil[String].minBy(_.length) failsWithRemoteError "List is empty",
        Remote.list("aaa", "a", "abcd", "ab").minBy(_.length) <-> "a"
      ),
      remoteTest("minByOption")(
        Remote.nil[String].minByOption(_.length) <-> None,
        Remote.list("aaa", "a", "abcd", "ab").minByOption(_.length) <-> Some("a")
      ),
      remoteTest("minOption")(
        Remote.nil[Int].minOption <-> None,
        Remote.list(1, 6, 4, 3, 8, 0, 4).minOption <-> Some(0)
      ),
      remoteTest("mkString")(
        Remote.nil[String].mkString <-> "",
        Remote.list("hello", "world", "!!!").mkString <-> "helloworld!!!",
        Remote.nil[Int].mkString <-> "",
        Remote.list(1, 2, 3).mkString <-> "123",
        Remote.list("hello", "world", "!!!").mkString("--") <-> "hello--world--!!!",
        Remote.list(1, 2, 3).mkString("/") <-> "1/2/3",
        Remote.list("hello", "world", "!!!").mkString("<", " ", ">") <-> "<hello world !!!>",
        Remote.list(1, 2, 3).mkString("(", ", ", ")") <-> "(1, 2, 3)"
      ),
      remoteTest("nonEmpty")(
        Remote.nil[Int].nonEmpty <-> false,
        Remote.list(1, 2, 3).nonEmpty <-> true
      ),
      remoteTest("padTo")(
        Remote.nil[Int].padTo(3, 11) <-> List(11, 11, 11),
        Remote.list(1, 2, 3).padTo(5, 11) <-> List(1, 2, 3, 11, 11),
        Remote.list(1, 2, 3).padTo(3, 11) <-> List(1, 2, 3)
      ),
      remoteTest("partition")(
        Remote.nil[Int].partition(_ % 2 === 0) <-> (List.empty[Int], List.empty[Int]),
        Remote.list(1, 2, 3, 4, 5).partition(_ % 2 === 0) <-> (List(2, 4), List(1, 3, 5))
      ),
      remoteTest("partitionMap")(
        Remote
          .nil[Int]
          .partitionMap((n: Remote[Int]) =>
            (n % 2 === 0).ifThenElse(ifTrue = Remote.left(n), ifFalse = Remote.right(n.toString))
          ) <-> (List.empty[Int], List.empty[String]),
        Remote
          .list(1, 2, 3, 4, 5)
          .partitionMap((n: Remote[Int]) =>
            (n % 2 === 0).ifThenElse(ifTrue = Remote.left(n), ifFalse = Remote.right(n.toString))
          ) <-> (List(2, 4), List("1", "3", "5"))
      ),
      remoteTest("patch")(
        Remote.nil[Int].patch(0, Remote.nil[Int], 0) <-> List.empty[Int],
        Remote.nil[Int].patch(0, Remote.list(1, 2, 3), 2) <-> List(1, 2, 3),
        Remote.list(1, 2, 3, 4).patch(0, Remote.list(5, 6, 7), 2) <-> List(5, 6, 7, 3, 4),
        Remote.list(1, 2, 3, 4).patch(2, Remote.list(5, 6, 7), 1) <-> List(1, 2, 5, 6, 7, 4)
      ),
      remoteTest("permutations")(
        Remote.nil[Int].permutations <-> List(List.empty[Int]),
        Remote.list(1, 2, 3).permutations <-> List(
          List(1, 2, 3),
          List(1, 3, 2),
          List(2, 1, 3),
          List(2, 3, 1),
          List(3, 1, 2),
          List(3, 2, 1)
        )
      ),
      remoteTest("prepended")(
        Remote.nil[Int].prepended(1) <-> List(1),
        Remote.list(2, 3).prepended(1) <-> List(1, 2, 3)
      ),
      remoteTest("prependedAll")(
        Remote.nil[Int].prependedAll(Remote.list(1, 2)) <-> List(1, 2),
        Remote.list(2, 3).prependedAll(Remote.list(0, 1)) <-> List(0, 1, 2, 3)
      ),
      remoteTest("product")(
        Remote.nil[Int].product <-> 1,
        Remote.list(1.0, 2.0, 3.0).product <-> 6.0
      ),
      remoteTest("reduce")(
        Remote.nil[Int].reduce(_ + _) failsWithRemoteError "List is empty",
        Remote.list(1, 2, 3).reduce(_ + _) <-> 6
      ),
      remoteTest("reduceLeft")(
        Remote.nil[Int].reduceLeft(_ + _) failsWithRemoteError "List is empty",
        Remote.list(1, 2, 3).reduceLeft(_ + _) <-> 6
      ),
      remoteTest("reduceLeftOption")(
        Remote.nil[Int].reduceLeftOption(_ + _) <-> None,
        Remote.list(1, 2, 3).reduceLeftOption(_ + _) <-> Some(6)
      ),
      remoteTest("reduceOption")(
        Remote.nil[Int].reduceOption(_ + _) <-> None,
        Remote.list(1, 2, 3).reduceOption(_ + _) <-> Some(6)
      ),
      remoteTest("reduceRight")(
        Remote.nil[Int].reduceRight(_ + _) failsWithRemoteError "List is empty",
        Remote.list(1.0, 2.0, 3.0).reduceRight(_ / _) <-> 1.5
      ),
      remoteTest("reduceRightOption")(
        Remote.nil[Int].reduceRightOption(_ + _) <-> None,
        Remote.list(1.0, 2.0, 3.0).reduceRightOption(_ / _) <-> Some(1.5)
      ),
      remoteTest("reverse")(
        Remote.nil[Int].reverse <-> List.empty[Int],
        Remote.list(1).reverse <-> List(1),
        Remote.list(1, 2, 3).reverse <-> List(3, 2, 1)
      ),
      remoteTest("reverse_:::")(
        Remote.list(1, 2, 3, 4).reverse_:::(Remote.list(5, 6, 7)) <-> List(7, 6, 5, 1, 2, 3, 4),
        Remote.list(1, 2, 3, 4).reverse_:::(Remote.nil[Int]) <-> List(1, 2, 3, 4),
        Remote.nil[Int].reverse_:::(Remote.list(1, 2, 3, 4)) <-> List(4, 3, 2, 1),
        Remote.nil[Int].reverse_:::(Remote.nil[Int]) <-> List.empty
      ),
      remoteTest("zipWithIndex")(
        Remote.nil[String].zipWithIndex <-> List.empty[(String, Int)],
        Remote.list("a", "b", "c").zipWithIndex <-> List("a" -> 0, "b" -> 1, "c" -> 2)
      ),
      remoteTest("sameElements")(
        Remote.nil[Int].sameElements(Remote.nil[Int]) <-> true,
        Remote.nil[Int].sameElements(Remote.list(1, 2, 3)) <-> false,
        Remote.list(1, 2, 3, 4).sameElements(Remote.list(1, 2, 3)) <-> false,
        Remote.list(1, 2).sameElements(Remote.list(1, 2, 3)) <-> false,
        Remote.list(1, 2, 3).sameElements(Remote.list(1, 2, 3)) <-> true
      ),
      remoteTest("List.fill")(
        List.fill(Remote(3))(Remote(1)) <-> List(1, 1, 1)
      )
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
}
