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
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory) @@ TestAspect.fromLayer(
      Runtime.addLogger(ZLogger.default.filterLogLevel(_ == LogLevel.Debug).map(_.foreach(println)))
    )
}
