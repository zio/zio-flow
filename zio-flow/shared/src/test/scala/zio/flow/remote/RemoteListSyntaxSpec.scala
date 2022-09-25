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
        Remote.list(1, 2, 3, 4).indexOf(3) <-> 2
      ),
      remoteTest("zipWithIndex")(
        Remote.nil[String].zipWithIndex <-> List.empty[(String, Int)],
        Remote.list("a", "b", "c").zipWithIndex <-> List("a" -> 0, "b" -> 1, "c" -> 2)
      )
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
}
