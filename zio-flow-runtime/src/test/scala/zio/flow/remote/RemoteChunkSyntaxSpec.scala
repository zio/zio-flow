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

import zio.flow.debug.TrackRemotes._
import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.{Chunk, Scope, ZLayer}
import zio.flow.{LocalContext, Remote}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test.{Spec, TestEnvironment}

object RemoteChunkSyntaxSpec extends RemoteSpecBase {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RemoteChunkSyntax")(
      remoteTest("Remote.emptyChunk")(
        Remote.emptyChunk[Int] <-> Chunk.empty[Int]
      ),
      remoteTest("Remote.chunk")(
        Remote.chunk(1, 2, 3) <-> Chunk(1, 2, 3)
      ),
      remoteTest("++")(
        Remote.chunk(1, 2, 3, 4) ++ Remote.chunk(5, 6, 7) <-> Chunk(1, 2, 3, 4, 5, 6, 7),
        Remote.chunk(1, 2, 3, 4) ++ Remote.emptyChunk[Int] <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int] ++ Remote.chunk(1, 2, 3, 4) <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int] ++ Remote.emptyChunk[Int] <-> Chunk.empty
      ),
      remoteTest("++:")(
        Remote.chunk(1, 2, 3, 4) ++: Remote.chunk(5, 6, 7) <-> Chunk(1, 2, 3, 4, 5, 6, 7),
        Remote.chunk(1, 2, 3, 4) ++: Remote.emptyChunk[Int] <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int] ++: Remote.chunk(1, 2, 3, 4) <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int] ++: Remote.emptyChunk[Int] <-> Chunk.empty
      ),
      remoteTest("+:")(
        Remote(1) +: Remote.emptyChunk[Int] <-> Chunk(1),
        Remote(1) +: Remote.chunk(2, 3) <-> Chunk(1, 2, 3)
      ),
      remoteTest(":+")(
        Remote.emptyChunk[Int] :+ Remote(1) <-> Chunk(1),
        Remote.chunk(2, 3) :+ Remote(1) <-> Chunk(2, 3, 1)
      ),
      remoteTest(":++")(
        Remote.chunk(1, 2, 3, 4) :++ Remote.chunk(5, 6, 7) <-> Chunk(1, 2, 3, 4, 5, 6, 7),
        Remote.chunk(1, 2, 3, 4) :++ Remote.emptyChunk[Int] <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int] :++ Remote.chunk(1, 2, 3, 4) <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int] :++ Remote.emptyChunk[Int] <-> Chunk.empty
      ),
      remoteTest("appended")(
        Remote.emptyChunk[Int].appended(Remote(1)) <-> Chunk(1),
        Remote.chunk(2, 3).appended(Remote(1)) <-> Chunk(2, 3, 1)
      ),
      remoteTest("appendedAll")(
        Remote.chunk(1, 2, 3, 4).appendedAll(Remote.chunk(5, 6, 7)) <-> Chunk(1, 2, 3, 4, 5, 6, 7),
        Remote.chunk(1, 2, 3, 4).appendedAll(Remote.emptyChunk[Int]) <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int].appendedAll(Remote.chunk(1, 2, 3, 4)) <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int].appendedAll(Remote.emptyChunk[Int]) <-> Chunk.empty
      ),
      remoteTest("apply")(
        Remote.emptyChunk[Int].apply(0) failsWithRemoteFailure ("get called on empty Option"),
        Remote.chunk(1, 2, 3).apply(1) <-> 2
      ),
      remoteTest("concat")(
        Remote.chunk(1, 2, 3, 4).concat(Remote.chunk(5, 6, 7)) <-> Chunk(1, 2, 3, 4, 5, 6, 7),
        Remote.chunk(1, 2, 3, 4).concat(Remote.emptyChunk[Int]) <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int].concat(Remote.chunk(1, 2, 3, 4)) <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int].concat(Remote.emptyChunk[Int]) <-> Chunk.empty
      ),
      remoteTest("contains")(
        Remote.emptyChunk[Int].contains(10) <-> false,
        Remote.chunk(1, 2, 3).contains(10) <-> false,
        Remote.chunk(9, 10, 11).contains(10) <-> true
      ),
      remoteTest("containsSlice")(
        Remote.emptyChunk[Int].containsSlice(Remote.chunk(1, 2)) <-> false,
        Remote.chunk(1, 2, 3, 4).containsSlice(Remote.chunk(2, 3)) <-> true,
        Remote.chunk(1, 2, 3).containsSlice(Remote.chunk(2, 3)) <-> true,
        Remote.chunk(1, 2).containsSlice(Remote.chunk(2, 3)) <-> false
      ),
      remoteTest("corresponds")(
        Remote
          .chunk(1, 2, 3)
          .corresponds(Remote.chunk("x", "xx", "xxx"))((n: Remote[Int], s: Remote[String]) => s.length === n) <-> true,
        Remote
          .chunk(1, 2, 1)
          .corresponds(Remote.chunk("x", "xx", "xxx"))((n: Remote[Int], s: Remote[String]) => s.length === n) <-> false
      ),
      remoteTest("count")(
        Remote.emptyChunk[Int].count(_ % 2 === 0) <-> 0,
        Remote.chunk(1, 2, 3, 4, 5).count(_ % 2 === 0) <-> 2
      ),
      remoteTest("diff")(
        Remote.chunk(1, 2, 3, 4).diff(Remote.chunk(2, 4)) <-> Chunk(1, 3),
        Remote.chunk(1, 2, 3, 4).diff(Remote.emptyChunk[Int]) <-> Chunk(1, 2, 3, 4),
        Remote.emptyChunk[Int].diff(Remote.chunk(1, 2)) <-> Chunk.empty
      ),
      remoteTest("distinct")(
        Remote.emptyChunk[Int].distinct <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3, 2, 1).distinct <-> Chunk(1, 2, 3)
      ),
      remoteTest("distinctBy")(
        Remote.chunk("abc", "axy", "bca", "bb", "c").distinctBy(_.length) <-> Chunk("abc", "bb", "c"),
        Remote.emptyChunk[String].distinctBy(_.length) <-> Chunk.empty[String]
      ),
      remoteTest("drop")(
        Remote.emptyChunk[Int].drop(10) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3).drop(0) <-> Chunk(1, 2, 3),
        Remote.chunk(1, 2, 3).drop(2) <-> Chunk(3),
        Remote.chunk(1, 2, 3).drop(4) <-> Chunk.empty[Int]
      ),
      remoteTest("dropWhile")(
        Remote.emptyChunk[Int].dropWhile(_ < 3) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3, 4).dropWhile(_ < 3) <-> Chunk(3, 4)
      ),
      remoteTest("endsWith")(
        Remote.emptyChunk[Int].endsWith(Remote.chunk(1, 2)) <-> false,
        Remote.chunk(1, 2, 3).endsWith(Remote.chunk(1, 2)) <-> false,
        Remote.chunk(0, 0, 1, 2).endsWith(Remote.chunk(1, 2)) <-> true
      ),
      remoteTest("exists")(
        Remote.emptyChunk[Int].exists(_ % 2 === 0) <-> false,
        Remote.chunk(1, 3, 5, 7).exists(_ % 2 === 0) <-> false,
        Remote.chunk(1, 3, 4, 7).exists(_ % 2 === 0) <-> true
      ),
      remoteTest("filter")(
        Remote.emptyChunk[Int].filter(_ % 2 === 0) <-> Chunk.empty[Int],
        Remote.chunk(1, 3, 5, 7).filter(_ % 2 === 0) <-> Chunk.empty[Int],
        Remote.chunk(1, 3, 4, 7).filter(_ % 2 === 0) <-> Chunk(4)
      ),
      remoteTest("filterNot")(
        Remote.emptyChunk[Int].filterNot(_ % 2 === 0) <-> Chunk.empty[Int],
        Remote.chunk(1, 3, 5, 7).filterNot(_ % 2 === 0) <-> Chunk(1, 3, 5, 7),
        Remote.chunk(1, 3, 4, 7).filterNot(_ % 2 === 0) <-> Chunk(1, 3, 7)
      ),
      remoteTest("find")(
        Remote.emptyChunk[Int].find(_ === 3) <-> None,
        Remote.chunk(1, 2, 4, 5).find(_ === 3) <-> None,
        Remote.chunk(1, 2, 3, 4).find(_ === 3) <-> Some(3),
        Remote.chunk("aaa", "b", "ccc", "d").find(_.length === 3) <-> Some("aaa")
      ),
      remoteTest("findLast")(
        Remote.emptyChunk[String].findLast(_.length === 3) <-> None,
        Remote.chunk("aa", "bb", "cc").findLast(_.length === 3) <-> None,
        Remote.chunk("aaa", "b", "ccc", "d").findLast(_.length === 3) <-> Some("ccc")
      ),
      remoteTest("flatMap")(
        Remote.emptyChunk[Int].flatMap(_ => Remote.chunk(1, 2, 3)) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3).flatMap((n: Remote[Int]) => Remote.chunk(n) ++ Remote.chunk(2, 3)) <-> Chunk(1, 2, 3, 2,
          2, 3, 3, 2, 3),
        Remote.chunk(1, 2, 3).flatMap(_ => Remote.emptyChunk[Int]) <-> Chunk.empty[Int]
      ),
      remoteTest("flatten")(
        Remote.emptyChunk[Chunk[Int]].flatten <-> Chunk.empty[Int],
        Remote.chunk(Remote.emptyChunk[Int], Remote.emptyChunk[Int]).flatten <-> Chunk.empty[Int],
        Remote.chunk(Remote.chunk(1, 2), Remote.chunk(3, 4)).flatten <-> Chunk(1, 2, 3, 4)
      ),
      remoteTest("foldLeft")(
        Remote.chunk(1.0, 2.0, 3.0).foldLeft(4.0)(_ / _) <-> Chunk(1.0, 2.0, 3.0).foldLeft(4.0)(_ / _),
        Remote.emptyChunk[Double].foldLeft(4.0)(_ / _) <-> 4.0
      ),
      remoteTest("foldRight")(
        Remote.chunk(1.0, 2.0, 3.0).foldRight(4.0)(_ / _) <-> Chunk(1.0, 2.0, 3.0).foldRight(4.0)(_ / _),
        Remote.emptyChunk[Double].foldRight(4.0)(_ / _) <-> 4.0
      ),
      remoteTest("forall")(
        Remote.emptyChunk[Int].forall(_ % 2 === 0) <-> true,
        Remote.chunk(1, 2, 3, 4).forall(_ % 2 === 0) <-> false,
        Remote.chunk(2, 4, 6, 8).forall(_ % 2 === 0) <-> true
      ),
      remoteTest("groupBy")(
        Remote
          .chunk("abc", "def", "ba", "", "a", "b", "c", "de")
          .groupBy(_.length) <-> Map(
          3 -> Chunk("abc", "def"),
          2 -> Chunk("ba", "de"),
          0 -> Chunk(""),
          1 -> Chunk("a", "b", "c")
        )
      ),
      remoteTest("groupByMap")(
        Remote
          .chunk("abc", "def", "ba", "", "a", "b", "c", "de")
          .groupMap(_.length)(s => s + s) <-> Map(
          3 -> Chunk("abcabc", "defdef"),
          2 -> Chunk("baba", "dede"),
          0 -> Chunk(""),
          1 -> Chunk("aa", "bb", "cc")
        )
      ),
      remoteTest("groupByMapReduce")(
        Remote
          .chunk("abc", "def", "ba", "", "a", "b", "c", "de")
          .groupMapReduce(_.length)(s => s.length)(_ + _) <-> Map(
          3 -> 6,
          2 -> 4,
          0 -> 0,
          1 -> 3
        )
      ),
      remoteTest("grouped")(
        Remote.emptyChunk[Int].grouped(3) <-> Chunk.empty[Chunk[Int]],
        Remote.chunk(1, 2, 3, 4, 5, 6, 7, 8).grouped(3) <-> Chunk(Chunk(1, 2, 3), Chunk(4, 5, 6), Chunk(7, 8))
      ),
      remoteTest("head")(
        Remote.emptyChunk[Int].head failsWithRemoteFailure "List is empty",
        Remote.chunk(1, 2, 3).head <-> 1
      ),
      remoteTest("headOption")(
        Remote.emptyChunk[Int].headOption <-> None,
        Remote.chunk(1, 2, 3).headOption <-> Some(1)
      ),
      remoteTest("indexOf")(
        Remote.emptyChunk[Int].indexOf(3) <-> -1,
        Remote.chunk(1, 2, 4, 5).indexOf(3) <-> -1,
        Remote.chunk(1, 2, 3, 4).indexOf(3) <-> 2,
        Remote.chunk(1, 2, 3, 4).indexOf(3, 2) <-> 2,
        Remote.chunk(1, 2, 3, 4, 4, 3).indexOf(3, 3) <-> 5
      ),
      remoteTest("indexOfSlice")(
        Remote.emptyChunk[Int].indexOfSlice(Chunk(2, 3)) <-> -1,
        Remote.chunk(1, 2, 3, 4, 5, 2, 3, 6).indexOfSlice(Chunk(2, 3)) <-> 1,
        Remote.chunk(1, 2, 3, 4, 5, 2, 3, 6).indexOfSlice(Chunk(2, 3), 1) <-> 1,
        Remote.chunk(1, 2, 3, 4, 5, 2, 3, 6).indexOfSlice(Chunk(2, 3), 3) <-> 5
      ),
      remoteTest("indexWhere")(
        Remote.emptyChunk[Int].indexWhere(_ % 2 === 0) <-> -1,
        Remote.chunk(1, 2, 3, 4, 5, 2, 3, 6).indexWhere(_ % 2 === 0) <-> 1,
        Remote.chunk(1, 2, 3, 4, 5, 2, 3, 6).indexWhere(_ % 2 === 0, 1) <-> 1,
        Remote.chunk(1, 2, 3, 4, 5, 2, 3, 6).indexWhere(_ % 2 === 0, 2) <-> 3
      ),
      remoteTest("init")(
        Remote.emptyChunk[Int].init failsWithRemoteFailure "List is empty",
        Remote.chunk(1).init <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3).init <-> Chunk(1, 2)
      ),
      remoteTest("inits")(
        Remote.emptyChunk[Int].inits <-> Chunk(Chunk.empty[Int]),
        Remote.chunk(1, 2, 3).inits <-> Chunk(Chunk(1, 2, 3), Chunk(1, 2), Chunk(1), Chunk())
      ),
      remoteTest("intersect")(
        Remote.emptyChunk[Int].intersect(Remote.chunk(3, 4, 5, 6)) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3, 4).intersect(Remote.chunk(3, 4, 5, 6)) <-> Chunk(3, 4),
        Remote.chunk(1, 2, 3, 4).intersect(Remote.emptyChunk[Int]) <-> Chunk.empty[Int]
      ),
      remoteTest("isDefinedAt")(
        Remote.emptyChunk[Int].isDefinedAt(0) <-> false,
        Remote.chunk(1, 2, 3).isDefinedAt(0) <-> true,
        Remote.chunk(1, 2, 3).isDefinedAt(2) <-> true,
        Remote.chunk(1, 2, 3).isDefinedAt(3) <-> false
      ),
      remoteTest("isEmpty")(
        Remote.emptyChunk[Int].isEmpty <-> true,
        Remote.chunk(1, 2, 3).isEmpty <-> false
      ),
      remoteTest("last")(
        Remote.emptyChunk[Int].last failsWithRemoteFailure "List is empty",
        Remote.chunk(1, 2, 3).last <-> 3
      ),
      remoteTest("lastIndexOf")(
        Remote.emptyChunk[Int].lastIndexOf(3) <-> -1,
        Remote.chunk(1, 2, 4, 4).lastIndexOf(3) <-> -1,
        Remote.chunk(1, 2, 3, 4, 3, 4).lastIndexOf(3) <-> 4,
        Remote.chunk(1, 2, 3, 4, 3, 4).lastIndexOf(3, 4) <-> 4,
        Remote.chunk(1, 2, 3, 4, 3, 4).lastIndexOf(3, 3) <-> 2
      ),
      remoteTest("lastIndexOfSlice")(
        Remote.emptyChunk[Int].lastIndexOfSlice(Remote.chunk(3, 4)) <-> -1,
        Remote.chunk(1, 2, 4, 4).lastIndexOfSlice(Remote.chunk(3, 4)) <-> -1,
        Remote.chunk(1, 2, 3, 4, 3, 4).lastIndexOfSlice(Remote.chunk(3, 4)) <-> 4,
        Remote.chunk(1, 2, 3, 4, 3, 4).lastIndexOfSlice(Remote.chunk(3, 4), 5) <-> 4,
        Remote.chunk(1, 2, 3, 4, 3, 4).lastIndexOfSlice(Remote.chunk(3, 4), 3) <-> 2
      ),
      remoteTest("lastIndexWhere")(
        Remote.emptyChunk[Int].lastIndexWhere(_ % 2 === 0) <-> -1,
        Remote.chunk(1, 2, 3, 4, 5, 2, 3, 6).lastIndexWhere(_ % 2 === 0) <-> 7,
        Remote.chunk(1, 2, 3, 4, 5, 2, 3, 6).lastIndexWhere(_ % 2 === 0, 6) <-> 5
      ),
      remoteTest("lastOption")(
        Remote.emptyChunk[Int].lastOption <-> None,
        Remote.chunk(1, 2, 3).lastOption <-> Some(3)
      ),
      remoteTest("length")(
        Remote.emptyChunk[Int].length <-> 0,
        Remote.chunk(1, 2, 3).length <-> 3
      ),
      remoteTest("map")(
        Remote.emptyChunk[Int].map(_ * 2) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3).map(_ * 2) <-> Chunk(2, 4, 6)
      ),
      remoteTest("max")(
        Remote.emptyChunk[Int].max failsWithRemoteFailure "List is empty",
        Remote.chunk(1, 6, 4, 3, 8, 0, 4).max <-> 8
      ),
      remoteTest("maxBy")(
        Remote.emptyChunk[String].maxBy(_.length) failsWithRemoteFailure "List is empty",
        Remote.chunk("aaa", "a", "abcd", "ab").maxBy(_.length) <-> "abcd"
      ),
      remoteTest("maxByOption")(
        Remote.emptyChunk[String].maxByOption(_.length) <-> None,
        Remote.chunk("aaa", "a", "abcd", "ab").maxByOption(_.length) <-> Some("abcd")
      ),
      remoteTest("maxOption")(
        Remote.emptyChunk[Int].maxOption <-> None,
        Remote.chunk(1, 6, 4, 3, 8, 0, 4).maxOption <-> Some(8)
      ),
      remoteTest("min")(
        Remote.emptyChunk[Int].min failsWithRemoteFailure "List is empty",
        Remote.chunk(1, 6, 4, 3, 8, 0, 4).min <-> 0
      ),
      remoteTest("minBy")(
        Remote.emptyChunk[String].minBy(_.length) failsWithRemoteFailure "List is empty",
        Remote.chunk("aaa", "a", "abcd", "ab").minBy(_.length) <-> "a"
      ),
      remoteTest("minByOption")(
        Remote.emptyChunk[String].minByOption(_.length) <-> None,
        Remote.chunk("aaa", "a", "abcd", "ab").minByOption(_.length) <-> Some("a")
      ),
      remoteTest("minOption")(
        Remote.emptyChunk[Int].minOption <-> None,
        Remote.chunk(1, 6, 4, 3, 8, 0, 4).minOption <-> Some(0)
      ),
      remoteTest("mkString")(
        Remote.emptyChunk[String].mkString <-> "",
        Remote.chunk("hello", "world", "!!!").mkString <-> "helloworld!!!",
        Remote.emptyChunk[Int].mkString <-> "",
        Remote.chunk(1, 2, 3).mkString <-> "123",
        Remote.chunk("hello", "world", "!!!").mkString("--") <-> "hello--world--!!!",
        Remote.chunk(1, 2, 3).mkString("/") <-> "1/2/3",
        Remote.chunk("hello", "world", "!!!").mkString("<", " ", ">") <-> "<hello world !!!>",
        Remote.chunk(1, 2, 3).mkString("(", ", ", ")") <-> "(1, 2, 3)"
      ),
      remoteTest("nonEmpty")(
        Remote.emptyChunk[Int].nonEmpty <-> false,
        Remote.chunk(1, 2, 3).nonEmpty <-> true
      ),
      remoteTest("padTo")(
        Remote.emptyChunk[Int].padTo(3, 11) <-> Chunk(11, 11, 11),
        Remote.chunk(1, 2, 3).padTo(5, 11) <-> Chunk(1, 2, 3, 11, 11),
        Remote.chunk(1, 2, 3).padTo(3, 11) <-> Chunk(1, 2, 3)
      ),
      remoteTest("partition")(
        Remote.emptyChunk[Int].partition(_ % 2 === 0) <-> ((Chunk.empty[Int], Chunk.empty[Int])),
        Remote.chunk(1, 2, 3, 4, 5).partition(_ % 2 === 0) <-> ((Chunk(2, 4), Chunk(1, 3, 5)))
      ),
      remoteTest("partitionMap")(
        Remote
          .emptyChunk[Int]
          .partitionMap((n: Remote[Int]) =>
            (n % 2 === 0).ifThenElse(ifTrue = Remote.left(n), ifFalse = Remote.right(n.toString))
          ) <-> ((Chunk.empty[Int], Chunk.empty[String])),
        Remote
          .chunk(1, 2, 3, 4, 5)
          .partitionMap((n: Remote[Int]) =>
            (n % 2 === 0).ifThenElse(ifTrue = Remote.left(n), ifFalse = Remote.right(n.toString))
          ) <-> ((Chunk(2, 4), Chunk("1", "3", "5")))
      ),
      remoteTest("patch")(
        Remote.emptyChunk[Int].patch(0, Remote.emptyChunk[Int], 0) <-> Chunk.empty[Int],
        Remote.emptyChunk[Int].patch(0, Remote.chunk(1, 2, 3), 2) <-> Chunk(1, 2, 3),
        Remote.chunk(1, 2, 3, 4).patch(0, Remote.chunk(5, 6, 7), 2) <-> Chunk(5, 6, 7, 3, 4),
        Remote.chunk(1, 2, 3, 4).patch(2, Remote.chunk(5, 6, 7), 1) <-> Chunk(1, 2, 5, 6, 7, 4)
      ),
      remoteTest("permutations")(
        Remote.emptyChunk[Int].permutations <-> Chunk(Chunk.empty[Int]),
        Remote.chunk(1, 2, 3).permutations <-> Chunk(
          Chunk(1, 2, 3),
          Chunk(1, 3, 2),
          Chunk(2, 1, 3),
          Chunk(2, 3, 1),
          Chunk(3, 1, 2),
          Chunk(3, 2, 1)
        )
      ),
      remoteTest("prepended")(
        Remote.emptyChunk[Int].prepended(1) <-> Chunk(1),
        Remote.chunk(2, 3).prepended(1) <-> Chunk(1, 2, 3)
      ),
      remoteTest("prependedAll")(
        Remote.emptyChunk[Int].prependedAll(Remote.chunk(1, 2)) <-> Chunk(1, 2),
        Remote.chunk(2, 3).prependedAll(Remote.chunk(0, 1)) <-> Chunk(0, 1, 2, 3)
      ),
      remoteTest("product")(
        Remote.emptyChunk[Int].product <-> 1,
        Remote.chunk(1.0, 2.0, 3.0).product <-> 6.0
      ),
      remoteTest("reduce")(
        Remote.emptyChunk[Int].reduce(_ + _) failsWithRemoteFailure "List is empty",
        Remote.chunk(1, 2, 3).reduce(_ + _) <-> 6
      ),
      remoteTest("reduceLeft")(
        Remote.emptyChunk[Int].reduceLeft(_ + _) failsWithRemoteFailure "List is empty",
        Remote.chunk(1, 2, 3).reduceLeft(_ + _) <-> 6
      ),
      remoteTest("reduceLeftOption")(
        Remote.emptyChunk[Int].reduceLeftOption(_ + _) <-> None,
        Remote.chunk(1, 2, 3).reduceLeftOption(_ + _) <-> Some(6)
      ),
      remoteTest("reduceOption")(
        Remote.emptyChunk[Int].reduceOption(_ + _) <-> None,
        Remote.chunk(1, 2, 3).reduceOption(_ + _) <-> Some(6)
      ),
      remoteTest("reduceRight")(
        Remote.emptyChunk[Int].reduceRight(_ + _) failsWithRemoteFailure "List is empty",
        Remote.chunk(1.0, 2.0, 3.0).reduceRight(_ / _) <-> 1.5
      ),
      remoteTest("reduceRightOption")(
        Remote.emptyChunk[Int].reduceRightOption(_ + _) <-> None,
        Remote.chunk(1.0, 2.0, 3.0).reduceRightOption(_ / _) <-> Some(1.5)
      ),
      remoteTest("reverse")(
        Remote.emptyChunk[Int].reverse <-> Chunk.empty[Int],
        Remote.chunk(1).reverse <-> Chunk(1),
        Remote.chunk(1, 2, 3).reverse <-> Chunk(3, 2, 1)
      ),
      remoteTest("sameElements")(
        Remote.emptyChunk[Int].sameElements(Remote.emptyChunk[Int]) <-> true,
        Remote.emptyChunk[Int].sameElements(Remote.chunk(1, 2, 3)) <-> false,
        Remote.chunk(1, 2, 3, 4).sameElements(Remote.chunk(1, 2, 3)) <-> false,
        Remote.chunk(1, 2).sameElements(Remote.chunk(1, 2, 3)) <-> false,
        Remote.chunk(1, 2, 3).sameElements(Remote.chunk(1, 2, 3)) <-> true
      ),
      remoteTest("scan")(
        Remote.emptyChunk[Int].scan(100)(_ / _) <-> Chunk(100),
        Remote.chunk(1, 2, 3).scan(100)(_ / _) <-> Chunk(100, 100, 50, 16)
      ),
      remoteTest("scanLeft")(
        Remote.emptyChunk[Int].scan(100)(_ / _) <-> Chunk(100),
        Remote.chunk(1, 2, 3).scan(100)(_ / _) <-> Chunk(100, 100, 50, 16)
      ),
      remoteTest("scanRight")(
        Remote.emptyChunk[Int].scanRight(100)(_ / _) <-> Chunk(100),
        Remote.chunk(1.0, 2.0, 3.0).scanRight(100.0)(_ / _) <-> Chunk(0.015, 66.66666666666667, 0.03, 100.0)
      ),
      remoteTest("segmentLength")(
        Remote.emptyChunk[Int].segmentLength(_ === 0) <-> 0,
        Remote.chunk(1, 2, 3, 0, 0).segmentLength(_ === 0) <-> 0,
        Remote.chunk(0, 0, 1, 2, 3).segmentLength(_ === 0) <-> 2,
        Remote.chunk(0, 0, 1, 2, 3, 0, 0, 0).segmentLength(_ === 0, 3) <-> 0,
        Remote.chunk(0, 0, 1, 2, 3, 0, 0, 0).segmentLength(_ === 0, 5) <-> 3
      ),
      remoteTest("size")(
        Remote.emptyChunk[Int].size <-> 0,
        Remote.chunk(1, 2, 3).size <-> 3
      ),
      remoteTest("slice")(
        Remote.emptyChunk[Int].slice(2, 3) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3, 4, 5, 6).slice(2, 3) <-> Chunk(3),
        Remote.chunk(1, 2, 3, 4, 5, 6).slice(2, 4) <-> Chunk(3, 4)
      ),
      remoteTest("sliding")(
        Remote.emptyChunk[Int].sliding(1, 1) <-> Chunk.empty[Chunk[Int]],
        Remote.chunk(0, 1, 2, 3, 3, 2, 3, 3, 3, 4, 5).sliding(1, 1) <-> Chunk(
          Chunk(0),
          Chunk(1),
          Chunk(2),
          Chunk(3),
          Chunk(3),
          Chunk(2),
          Chunk(3),
          Chunk(3),
          Chunk(3),
          Chunk(4),
          Chunk(5)
        ),
        Remote.chunk(0, 1, 2, 3, 3, 2, 3, 3, 3, 4, 5).sliding(1, 2) <-> Chunk(
          Chunk(0),
          Chunk(2),
          Chunk(3),
          Chunk(3),
          Chunk(3),
          Chunk(5)
        ),
        Remote.chunk(0, 1, 2, 3, 3, 2, 3, 3, 3, 4, 5).sliding(3, 1) <-> Chunk(
          Chunk(0, 1, 2),
          Chunk(1, 2, 3),
          Chunk(2, 3, 3),
          Chunk(3, 3, 2),
          Chunk(3, 2, 3),
          Chunk(2, 3, 3),
          Chunk(3, 3, 3),
          Chunk(3, 3, 4),
          Chunk(3, 4, 5)
        )
      ),
      remoteTest("span")(
        Remote.emptyChunk[Int].span(_ < 3) <-> ((Chunk.empty[Int], Chunk.empty[Int])),
        Remote.chunk(1, 2, 3, 4, 5).span(_ < 3) <-> ((Chunk(1, 2), Chunk(3, 4, 5)))
      ),
      remoteTest("splitAt")(
        Remote.emptyChunk[Int].splitAt(3) <-> ((Chunk.empty[Int], Chunk.empty[Int])),
        Remote.chunk(1, 2, 3, 4, 5).splitAt(3) <-> ((Chunk(1, 2, 3), Chunk(4, 5))),
        Remote.chunk(1, 2, 3, 4, 5).splitAt(0) <-> ((Chunk.empty, Chunk(1, 2, 3, 4, 5))),
        Remote.chunk(1, 2, 3, 4, 5).splitAt(6) <-> ((Chunk(1, 2, 3, 4, 5), Chunk.empty))
      ),
//      remoteTest("startsWith")(
//        Remote.emptyChunk[Int].startsWith(Remote.chunk(1, 2)) <-> false,
//        Remote.chunk(1, 2, 3).startsWith(Remote.chunk(1, 2)) <-> true,
//        Remote.chunk(0, 0, 1, 2).startsWith(Remote.chunk(1, 2)) <-> false
//      ),
      remoteTest("sum")(
        Remote.emptyChunk[Int].sum <-> 0,
        Remote.chunk(1, 2, 3, 4).sum <-> (1 + 2 + 3 + 4)
      ),
      remoteTest("tail")(
        Remote.emptyChunk[Int].tail failsWithRemoteFailure "List is empty",
        Remote.chunk(1).tail <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3).tail <-> Chunk(2, 3)
      ),
      remoteTest("tails")(
        Remote.emptyChunk[Int].tails <-> Chunk(Chunk.empty[Int]),
        Remote.chunk(1, 2, 3).tails <-> Chunk(Chunk(1, 2, 3), Chunk(2, 3), Chunk(3), Chunk())
      ),
      remoteTest("take")(
        Remote.emptyChunk[Int].take(2) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3, 4).take(2) <-> Chunk(1, 2)
      ),
      remoteTest("takeRight")(
        Remote.emptyChunk[Int].takeRight(2) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3, 4).takeRight(2) <-> Chunk(3, 4)
      ),
      remoteTest("takeWhile")(
        Remote.emptyChunk[Int].takeWhile(_ < 3) <-> Chunk.empty[Int],
        Remote.chunk(1, 2, 3, 4).takeWhile(_ < 3) <-> Chunk(1, 2)
      ),
      remoteTest("toList")(
        Remote.chunk(1, 2, 3).toList <-> List(1, 2, 3)
      ),
      remoteTest("toSet")(
        Remote.chunk(1, 2, 3, 3, 2, 1).toSet <-> Set(1, 2, 3)
      ),
      remoteTest("unzip")(
        Remote.emptyChunk[(Int, String)].unzip[Int, String] <-> ((Chunk.empty[Int], Chunk.empty[String])),
        Remote.chunk((1, "x"), (2, "y"), (3, "z")).unzip[Int, String] <-> ((Chunk(1, 2, 3), Chunk("x", "y", "z")))
      ),
      remoteTest("unzip3")(
        Remote
          .emptyChunk[(Int, String, Double)]
          .unzip3[Int, String, Double] <-> ((Chunk.empty[Int], Chunk.empty[String], Chunk.empty[Double])),
        Remote
          .chunk((1, "x", 0.1), (2, "y", 0.2), (3, "z", 0.3))
          .unzip3[Int, String, Double] <-> ((Chunk(1, 2, 3), Chunk("x", "y", "z"), Chunk(0.1, 0.2, 0.3)))
      ),
      remoteTest("zip")(
        Remote.emptyChunk[Int].zip(Remote.emptyChunk[String]) <-> Chunk.empty[(Int, String)],
        Remote.emptyChunk[Int].zip(Remote.chunk("x", "y")) <-> Chunk.empty[(Int, String)],
        Remote.chunk(1, 2).zip(Remote.emptyChunk[String]) <-> Chunk.empty[(Int, String)],
        Remote.chunk(1, 2).zip(Remote.chunk("x", "y")) <-> Chunk((1, "x"), (2, "y")),
        Remote.chunk(1, 2, 3).zip(Remote.chunk("x", "y")) <-> Chunk((1, "x"), (2, "y")),
        Remote.chunk(1, 2).zip(Remote.chunk("x", "y", "z")) <-> Chunk((1, "x"), (2, "y"))
      ),
      remoteTest("zipAll")(
        Remote.emptyChunk[Int].zipAll(Remote.emptyChunk[String], -1, "-") <-> Chunk.empty[(Int, String)],
        Remote.emptyChunk[Int].zipAll(Remote.chunk("x", "y"), -1, "-") <-> Chunk((-1, "x"), (-1, "y")),
        Remote.chunk(1, 2).zipAll(Remote.emptyChunk[String], -1, "-") <-> Chunk((1, "-"), (2, "-")),
        Remote.chunk(1, 2).zipAll(Remote.chunk("x", "y"), -1, "-") <-> Chunk((1, "x"), (2, "y")),
        Remote.chunk(1, 2, 3).zipAll(Remote.chunk("x", "y"), -1, "-") <-> Chunk((1, "x"), (2, "y"), (3, "-")),
        Remote.chunk(1, 2).zipAll(Remote.chunk("x", "y", "z"), -1, "-") <-> Chunk((1, "x"), (2, "y"), (-1, "z"))
      ),
      remoteTest("zipWithIndex")(
        Remote.emptyChunk[String].zipWithIndex <-> Chunk.empty[(String, Int)],
        Remote.chunk("a", "b", "c").zipWithIndex <-> Chunk("a" -> 0, "b" -> 1, "c" -> 2)
      ),
      remoteTest("chunk.fill")(
        Chunk.fill(Remote(3))(Remote(1)) <-> Chunk(1, 1, 1)
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
}
