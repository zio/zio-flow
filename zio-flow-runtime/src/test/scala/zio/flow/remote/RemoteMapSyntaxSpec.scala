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
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory) @@ TestAspect.fromLayer(
      Runtime.addLogger(ZLogger.default.filterLogLevel(_ == LogLevel.Debug).map(_.foreach(println)))
    )
}
