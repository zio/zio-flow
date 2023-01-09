/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{LocalContext, Remote}
import zio.test.{Spec, TestAspect, TestEnvironment}
import zio.{LogLevel, Runtime, Scope, ZLayer, ZLogger}

object RemoteMapSyntaxSpec extends RemoteSpecBase {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RemoteMapSyntax")(
      remoteTest("Remote.map")(
        Remote.map("hello" -> 1, "world" -> 2) <-> Map("hello" -> 1, "world" -> 2),
        Remote.map[String, Int]() <-> Map.empty[String, Int]
      ),
      remoteTest("+")(
        (Remote.map("hello" -> 1) + ("world" -> 2)) <-> Map("hello" -> 1, "world" -> 2)
      ),
      remoteTest("++")(
        (Remote.map("hello" -> 1) ++ Remote.map("world" -> 2)) <-> Map("hello" -> 1, "world" -> 2),
        (Remote.map("hello" -> 1) ++ (Remote.map("hello" -> 10, "world" -> 2))) <-> Map("hello" -> 10, "world" -> 2)
      ),
      remoteTest("-")(
        (Remote.map("hello" -> 1, "world" -> 2) - "world") <-> Map("hello" -> 1),
        (Remote.map("hello" -> 1, "world" -> 2) - "something") <-> Map("hello" -> 1, "world" -> 2)
      ),
      remoteTest("--")(
        (Remote.map("hello" -> 1, "world" -> 2) -- List("world")) <-> Map("hello" -> 1),
        (Remote.map("hello" -> 1, "world" -> 2) -- List("hello", "world")) <-> Map.empty[String, Int],
        (Remote.map("hello" -> 1, "world" -> 2) -- List("something")) <-> Map("hello" -> 1, "world" -> 2)
      ),
      remoteTest("apply")(
        Remote.map("hello" -> 1, "world" -> 2)("hello") <-> 1,
        Remote.map("hello" -> 1, "world" -> 2)("unknown") failsWithRemoteFailure ("get called on empty Option"),
        Remote.map[String, Int]()("x") failsWithRemoteFailure ("get called on empty Option")
      ),
      remoteTest("applyOrElse")(
        Remote.map("hello" -> 1, "world" -> 2).applyOrElse("hello", (k: Remote[String]) => k.length) <-> 1,
        Remote.map("hello" -> 1, "world" -> 2).applyOrElse("unknown", (k: Remote[String]) => k.length) <-> 7
      ),
      remoteTest("concat")(
        (Remote.map("hello" -> 1) concat Remote.map("world" -> 2)) <-> Map("hello" -> 1, "world" -> 2),
        (Remote.map("hello" -> 1) concat (Remote.map("hello" -> 10, "world" -> 2))) <-> Map("hello" -> 10, "world" -> 2)
      ),
      remoteTest("contains")(
        Remote.map("hello" -> 1, "world" -> 2).contains("hello") <-> true,
        Remote.map("hello" -> 1, "world" -> 2).contains("unknown") <-> false,
        Remote.map[String, Int]().contains("x") <-> false
      ),
      remoteTest("corresponds")(
        Remote
          .map(1 -> 10, 2 -> 20, 3 -> 30)
          .corresponds(Remote.list(11, 22, 33))((a: Remote[(Int, Int)], b: Remote[Int]) =>
            (a._1 + a._2) === b
          ) <-> true,
        Remote
          .map(1 -> 10, 2 -> 20, 3 -> 0)
          .corresponds(Remote.list(11, 22, 33))((a: Remote[(Int, Int)], b: Remote[Int]) =>
            (a._1 + a._2) === b
          ) <-> false
      ),
      remoteTest("count")(
        Remote
          .map(1 -> 10, 2 -> 21, 3 -> 5)
          .count(pair => pair._2 <= 10) <-> 2
      ),
      remoteTest("drop")(
        Remote.map('a' -> 1).drop(1) <-> Map.empty[Char, Int],
        Remote.map('a' -> 1, 'b' -> 2).drop(1) <-> Map('b' -> 2),
        Remote.map('a' -> 1, 'b' -> 2).updated('a', 3).drop(1) <-> Map('b' -> 2)
      ),
      remoteTest("dropRight")(
        Remote.map('a' -> 1).dropRight(1) <-> Map.empty[Char, Int],
        Remote.map('a' -> 1, 'b' -> 2).dropRight(1) <-> Map('a' -> 1),
        Remote.map('a' -> 1, 'b' -> 2).updated('a', 3).dropRight(1) <-> Map('a' -> 3)
      ),
      remoteTest("dropWhile")(
        Remote.map('a' -> 1, 'b' -> 2, 'c' -> 3).dropWhile(_._2 < 3) <-> Map('c' -> 3)
      ),
      remoteTest("empty")(
        Remote.map("hello" -> 1).empty <-> Map.empty[String, Int]
      ),
      remoteTest("exists")(
        Remote.map[String, Int]().exists(_._1.startsWith("h")) <-> false,
        Remote.map("hello" -> 1).exists(_._1.startsWith("h")) <-> true
      ),
      remoteTest("filter")(
        Remote.map[String, Int]().filter(_._1.startsWith("h")) <-> Map.empty[String, Int],
        Remote.map("hello" -> 1, "world" -> 2).filter(_._1.startsWith("h")) <-> Map("hello" -> 1)
      ),
      remoteTest("filterNot")(
        Remote.map[String, Int]().filterNot(_._1.startsWith("h")) <-> Map.empty[String, Int],
        Remote.map("hello" -> 1, "world" -> 2).filterNot(_._1.startsWith("h")) <-> Map("world" -> 2)
      ),
      remoteTest("find")(
        Remote.map("hello" -> 100, "world" -> 200).find(_._2 > 100) <-> Some(("world" -> 200))
      ),
      remoteTest("flatMap")(
        Remote
          .map("hello" -> 1, "world" -> 2)
          .flatMap[String, Int](kv =>
            Remote.map(Remote.tuple2(((kv._1 + kv._2.toString), Remote(100))), Remote.tuple2(("other", kv._2)))
          ) <-> Map(
          "hello1" -> 100,
          "world2" -> 100,
          "other"  -> 2
        )
      ),
      remoteTest("fold")(
        Remote
          .map(1 -> 10, 2 -> 20, 3 -> 30)
          .fold((0, 0))((kv1, kv2) => (kv1._1 + kv2._1, kv1._2 + kv2._2))
          .<->((6, 60))
      ),
      remoteTest("foldLeft")(
        Remote
          .map(1 -> 10, 2 -> 20, 3 -> 30)
          .foldLeft(0)((n, kv) => n + kv._1 + kv._2) <-> 66
      ),
      remoteTest("foldRight")(
        Remote
          .map(1 -> 10, 2 -> 20, 3 -> 30)
          .foldRight(0)((kv, n) => n + kv._1 + kv._2) <-> 66
      ),
      remoteTest("forall")(
        Remote
          .map(1 -> 10, 2 -> 20, 3 -> 30)
          .forall(kv => kv._2 === (kv._1 * 10)) <-> true,
        Remote
          .map(1 -> 10, 2 -> 21, 3 -> 30)
          .forall(kv => kv._2 === (kv._1 * 10)) <-> false
      ),
      remoteTest("get")(
        Remote.map("hello" -> 1, "world" -> 2).get("hello") <-> Some(1),
        Remote.map("hello" -> 1, "world" -> 2).get("unknown") <-> None,
        Remote.map[String, Int]().get("x") <-> None
      ),
      remoteTest("getOrElse")(
        Remote.map("hello" -> 1, "world" -> 2).getOrElse("hello", 1000) <-> 1,
        Remote.map("hello" -> 1, "world" -> 2).getOrElse("unknown", 1000) <-> 1000,
        Remote.map[String, Int]().getOrElse("x", 1000) <-> 1000
      ),
      remoteTest("groupBy")(
        Remote
          .map("abc" -> 'a', "def" -> 'd', "ba" -> 'b', "" -> ' ', "a" -> 'a', "b" -> 'b', "c" -> 'c', "de" -> 'd')
          .groupBy(_._1.length) <-> Map(
          3 -> Map("abc" -> 'a', "def" -> 'd'),
          2 -> Map("ba" -> 'b', "de" -> 'd'),
          0 -> Map("" -> ' '),
          1 -> Map("a" -> 'a', "b" -> 'b', "c" -> 'c')
        )
      ),
      remoteTest("groupByMap")(
        Remote
          .map("abc" -> 'a', "def" -> 'd', "ba" -> 'b', "" -> ' ', "a" -> 'a', "b" -> 'b', "c" -> 'c', "de" -> 'd')
          .groupMap(_._1.length)(s => s._1 + s._1) <-> Map(
          3 -> List("abcabc", "defdef"),
          2 -> List("baba", "dede"),
          0 -> List(""),
          1 -> List("aa", "bb", "cc")
        )
      ),
      remoteTest("groupByMapReduce")(
        Remote
          .map("abc" -> 'a', "def" -> 'd', "ba" -> 'b', "" -> ' ', "a" -> 'a', "b" -> 'b', "c" -> 'c', "de" -> 'd')
          .groupMapReduce(_._1.length)(s => s._1.length)(_ + _) <-> Map(
          3 -> 6,
          2 -> 4,
          0 -> 0,
          1 -> 3
        )
      ),
      remoteTest("grouped")(
        Remote
          .map("abc" -> 'a', "def" -> 'd', "ba" -> 'b', "" -> ' ', "a" -> 'a', "b" -> 'b', "c" -> 'c', "de" -> 'd')
          .grouped(2) <-> List(
          Map("abc" -> 'a', "def" -> 'd'),
          Map("ba"  -> 'b', ""    -> ' '),
          Map("a"   -> 'a', "b"   -> 'b'),
          Map("c"   -> 'c', "de"  -> 'd')
        )
      ),
      remoteTest("head")(
        Remote.map("a" -> 1, "b" -> 2).head.<->(("a" -> 1))
      ),
      remoteTest("headOption")(
        Remote.map("a" -> 1, "b" -> 2).headOption <-> Some(("a" -> 1)),
        Remote.emptyMap[String, Int].headOption <-> None
      ),
      remoteTest("init")(
        Remote.map("a" -> 1, "b" -> 2, "c" -> 3).init <-> Map("a" -> 1, "b" -> 2),
        Remote.emptyMap[String, Int].init failsWithRemoteFailure "List is empty"
      ),
      remoteTest("inits")(
        Remote.emptyMap[String, Int].inits <-> List(Map.empty[String, Int]),
        Remote.map("a" -> 1, "b" -> 2, "c" -> 3).inits <-> List(
          Map("a" -> 1, "b" -> 2, "c" -> 3),
          Map("a" -> 1, "b" -> 2),
          Map("a" -> 1),
          Map.empty[String, Int]
        )
      ),
      remoteTest("isDefinedAt")(
        Remote.map("hello" -> 1, "world" -> 2).isDefinedAt("hello") <-> true,
        Remote.map("hello" -> 1, "world" -> 2).isDefinedAt("unknown") <-> false,
        Remote.map[String, Int]().isDefinedAt("x") <-> false
      ),
      remoteTest("isEmpty")(
        Remote.map("a" -> 1, "b" -> 2).isEmpty <-> false,
        Remote.emptyMap[String, Int].isEmpty <-> true
      ),
      remoteTest("keySet")(
        Remote.map("a" -> 1, "b" -> 2, "c" -> 3).keySet <-> Set("a", "b", "c"),
        Remote.emptyMap[String, Int].keySet <-> Set.empty[String]
      ),
      remoteTest("keys")(
        Remote.map("a" -> 1, "b" -> 2, "c" -> 3).keys <-> List("a", "b", "c"),
        Remote.emptyMap[String, Int].keys <-> List.empty[String]
      ),
      remoteTest("last")(
        Remote.map("a" -> 1, "b" -> 2).last.<->(("b" -> 2))
      ),
      remoteTest("lastOption")(
        Remote.map("a" -> 1, "b" -> 2).lastOption <-> Some(("b" -> 2)),
        Remote.emptyMap[String, Int].lastOption <-> None
      ),
      remoteTest("lift")(
        Remote.set("a", "b", "c", "d").map[Option[Int]](Remote.map("a" -> 1, "b" -> 2).lift) <-> Set(
          Some(1),
          Some(2),
          None
        )
      ),
      remoteTest("map")(
        Remote.map("a" -> 1, "b" -> 2).map(pair => (pair._2, pair._1)) <-> Map(1 -> "a", 2 -> "b"),
        Remote.emptyMap[String, Int].map(pair => (pair._2, pair._1)) <-> Map.empty[Int, String]
      ),
      remoteTest("mkString")(
        Remote.map("a" -> 1, "b" -> 2).mkString <-> "(a,1)(b,2)",
        Remote.map("a" -> 1, "b" -> 2).mkString("-") <-> "(a,1)-(b,2)",
        Remote.map("a" -> 1, "b" -> 2).mkString("<<", "-", ">>") <-> "<<(a,1)-(b,2)>>"
      ),
      remoteTest("nonEmpty")(
        Remote.map("a" -> 1, "b" -> 2).nonEmpty <-> true,
        Remote.emptyMap[String, Int].nonEmpty <-> false
      ),
      remoteTest("partition")(
        Remote
          .map("aa" -> 1, "a" -> 0, "bbb" -> 10, "cc" -> 11, "d" -> 100)
          .partition(pair => pair._1.length > 1)
          .<->(
            (
              Map(
                "aa"  -> 1,
                "bbb" -> 10,
                "cc"  -> 11
              ),
              Map(
                "a" -> 0,
                "d" -> 100
              )
            )
          )
      ),
      remoteTest("partitionMap")(
        Remote
          .map("aa" -> 1, "a" -> 0, "bbb" -> 10, "cc" -> 11, "d" -> 100)
          .partitionMap(pair => (pair._1.length > 1).ifThenElse(Remote.left(pair._2), Remote.right(pair._2)))
          .<->(
            (
              List(1, 10, 11),
              List(0, 100)
            )
          )
      ),
      remoteTest("reduce")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).reduce((a, b) => (a._1 + b._1, a._2 + b._2)).<->((6, 60))
      ),
      remoteTest("reduceOption")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).reduceOption((a, b) => (a._1 + b._1, a._2 + b._2)) <-> Some((6, 60)),
        Remote.emptyMap[Int, Int].reduceOption((a, b) => (a._1 + b._1, a._2 + b._2)) <-> None
      ),
      remoteTest("reduceLeft")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).reduceLeft((a, b) => (a._1 + b._1, a._2 + b._2)).<->((6, 60))
      ),
      remoteTest("reduceLeftOption")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).reduceLeftOption((a, b) => (a._1 + b._1, a._2 + b._2)) <-> Some((6, 60)),
        Remote.emptyMap[Int, Int].reduceLeftOption((a, b) => (a._1 + b._1, a._2 + b._2)) <-> None
      ),
      remoteTest("reduceRight")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).reduceRight((a, b) => (a._1 + b._1, a._2 + b._2)).<->((6, 60))
      ),
      remoteTest("reduceRightOption")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).reduceRightOption((a, b) => (a._1 + b._1, a._2 + b._2)) <-> Some((6, 60)),
        Remote.emptyMap[Int, Int].reduceRightOption((a, b) => (a._1 + b._1, a._2 + b._2)) <-> None
      ),
      remoteTest("removed")(
        Remote.map("a" -> 1, "b" -> 2).removed("a") <-> Map("b" -> 2),
        Remote.map("a" -> 1, "b" -> 2).removed("c") <-> Map("a" -> 1, "b" -> 2)
      ),
      remoteTest("removedAll")(
        (Remote.map("hello" -> 1, "world" -> 2) removedAll List("world")) <-> Map("hello" -> 1),
        (Remote.map("hello" -> 1, "world" -> 2) removedAll List("hello", "world")) <-> Map.empty[String, Int],
        (Remote.map("hello" -> 1, "world" -> 2) removedAll List("something")) <-> Map("hello" -> 1, "world" -> 2)
      ),
      remoteTest("scan")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).scan((0, 0))((a, b) => (a._1 + b._1, a._2 + b._2)) <-> List(
          (0, 0),
          (1, 10),
          (3, 30),
          (6, 60)
        )
      ),
      remoteTest("scanLeft")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).scanLeft((0, 0))((a, b) => (a._1 + b._1, a._2 + b._2)) <-> List(
          (0, 0),
          (1, 10),
          (3, 30),
          (6, 60)
        )
      ),
      remoteTest("scanRight")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).scanRight((0, 0))((a, b) => (a._1 + b._1, a._2 + b._2)) <-> List(
          (6, 60),
          (5, 50),
          (3, 30),
          (0, 0)
        )
      ),
      remoteTest("size")(
        Remote.map(1 -> 10, 2 -> 20, 3 -> 30).size <-> 3,
        Remote.emptyMap[Int, Int].size <-> 0
      ),
      remoteTest("slice")(
        Remote
          .map("aa" -> 1, "a" -> 0, "bbb" -> 10, "cc" -> 11, "d" -> 100)
          .slice(2, 4) <-> Map("bbb" -> 10, "cc" -> 11)
      ),
      remoteTest("sliding")(
        Remote
          .map("aa" -> 1, "a" -> 0, "bbb" -> 10, "cc" -> 11, "d" -> 100)
          .sliding(3) <-> List(
          Map("aa"  -> 1, "a"   -> 0, "bbb" -> 10),
          Map("a"   -> 0, "bbb" -> 10, "cc" -> 11),
          Map("bbb" -> 10, "cc" -> 11, "d"  -> 100)
        )
      ),
      remoteTest("span")(
        Remote
          .map("aa" -> 1, "a" -> 0, "bbb" -> 10, "cc" -> 5, "d" -> 100)
          .span(pair => pair._2 < 10)
          .<->((Map("aa" -> 1, "a" -> 0), Map("bbb" -> 10, "cc" -> 5, "d" -> 100)))
      ),
      remoteTest("splitAt")(
        Remote
          .map("aa" -> 1, "a" -> 0, "bbb" -> 10, "cc" -> 5, "d" -> 100)
          .splitAt(2)
          .<->((Map("aa" -> 1, "a" -> 0), Map("bbb" -> 10, "cc" -> 5, "d" -> 100)))
      ),
      remoteTest("tail")(
        Remote.map("a" -> 1, "b" -> 2, "c" -> 3).tail <-> Map("b" -> 2, "c" -> 3),
        Remote.emptyMap[String, Int].tail failsWithRemoteFailure "List is empty"
      ),
      remoteTest("tails")(
        Remote.emptyMap[String, Int].tails <-> List(Map.empty[String, Int]),
        Remote.map("a" -> 1, "b" -> 2, "c" -> 3).tails <-> List(
          Map("a" -> 1, "b" -> 2, "c" -> 3),
          Map("b" -> 2, "c" -> 3),
          Map("c" -> 3),
          Map.empty[String, Int]
        )
      ),
      remoteTest("take")(
        Remote
          .map("aa" -> 1, "a" -> 0, "bbb" -> 10, "cc" -> 5, "d" -> 100)
          .take(2) <-> Map("aa" -> 1, "a" -> 0)
      ),
      remoteTest("takeRight")(
        Remote
          .map("cc" -> 5, "d" -> 100)
          .takeRight(2) <-> Map("cc" -> 5, "d" -> 100)
      ),
      remoteTest("updated")(
        Remote.map("hello" -> 1).updated("world", 2) <-> Map("hello" -> 1, "world" -> 2),
        Remote.map("hello" -> 1, "world" -> 2).updated("world", 3) <-> Map("hello" -> 1, "world" -> 3),
        Remote.map("hello" -> 1, "world" -> 2).updated("hello", 3) <-> Map("hello" -> 3, "world" -> 2)
      ),
      remoteTest("toList")(
        Remote.map("hello" -> 1, "world" -> 2).toList <-> List(("hello", 1), ("world", 2))
      ),
      remoteTest("toSet")(
        Remote.map("hello" -> 1, "world" -> 2).toSet <-> Set(("hello", 1), ("world", 2))
      ),
      remoteTest("unzip")(
        Remote.map("hello" -> 1, "world" -> 2).unzip.<->((List("hello", "world"), List(1, 2)))
      ),
      remoteTest("values")(
        Remote.map("hello" -> 1, "world" -> 2).values <-> List(1, 2)
      ),
      remoteTest("zip")(
        Remote.map("hello" -> 1, "world" -> 2).zip(List('x', 'y')) <-> List((("hello", 1), 'x'), (("world", 2), 'y'))
      ),
      remoteTest("zipAll")(
        Remote.map("hello" -> 1, "world" -> 2).zipAll(List('x'), ("???", 0), '!') <-> List(
          (("hello", 1), 'x'),
          (("world", 2), '!')
        ),
        Remote.map("hello" -> 1).zipAll(List('x', 'y'), ("???", 0), '!') <-> List(
          (("hello", 1), 'x'),
          (("???", 0), 'y')
        )
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory) @@ TestAspect.fromLayer(
      Runtime.addLogger(ZLogger.default.filterLogLevel(_ == LogLevel.Debug).map(_.foreach(println)))
    )
}
