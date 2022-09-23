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

import zio.{Scope, ZIO, ZLayer}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow._
import zio.test.{Spec, TestEnvironment, TestResult}

object RemoteOptionSyntaxSpec extends RemoteSpecBase {

  private def optionTest(name: String)(
    cases: ZIO[RemoteContext with LocalContext, Nothing, TestResult]*
  ): Spec[RemoteContext with LocalContext, Nothing] =
    test(name)(ZIO.collectAll(cases.toList).map(TestResult.all(_: _*)))

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RemoteOptionSyntax")(
      optionTest("contains")(
        Remote.none.contains(2) <-> false,
        Remote.some(12).contains(12) <-> true,
        Remote.some(12).contains(11) <-> false
      ),
      optionTest("exists")(
        Remote.none[Int].exists((n: Remote[Int]) => n === 12) <-> false,
        Remote.some(12).exists((n: Remote[Int]) => n === 12) <-> true,
        Remote.some(12).exists((n: Remote[Int]) => n === 11) <-> false
      ),
      optionTest("filter")(
        Remote.none[Int].filter((n: Remote[Int]) => n === 12) <-> None,
        Remote.some(12).filter((n: Remote[Int]) => n === 12) <-> Some(12),
        Remote.some(12).filter((n: Remote[Int]) => n === 11) <-> None
      ),
      optionTest("filterNot")(
        Remote.none[Int].filterNot((n: Remote[Int]) => n === 12) <-> None,
        Remote.some(12).filterNot((n: Remote[Int]) => n === 12) <-> None,
        Remote.some(12).filterNot((n: Remote[Int]) => n === 11) <-> Some(12)
      ),
      optionTest("flatMap")(
        Remote.some(12).flatMap((n: Remote[Int]) => Remote.some(n + 1)) <-> Some(13),
        Remote.none[Int].flatMap((n: Remote[Int]) => Remote.some(n + 1)) <-> None,
        Remote.some(12).flatMap((_: Remote[Int]) => Remote.none[Int]) <-> None
      ),
      optionTest("fold")(
        Remote.some(12).fold(Remote(0))((x: Remote[Int]) => x * 2) <-> 24,
        Remote.none[Int].fold(Remote(0))((x: Remote[Int]) => x * 2) <-> 0
      ),
      optionTest("foldLeft")(
        Remote.some(12).foldLeft(Remote(1))(t => t._1 + t._2) <-> 13,
        Remote.none[Int].foldLeft(Remote(1))(t => t._1 + t._2) <-> 1
      ),
      optionTest("foldRight")(
        Remote.some(12).foldRight(Remote(1))(t => t._1 + t._2) <-> 13,
        Remote.none[Int].foldRight(Remote(1))(t => t._1 + t._2) <-> 1
      ),
      optionTest("forall")(
        Remote.none[Int].forall((n: Remote[Int]) => n === 12) <-> false,
        Remote.some(12).forall((n: Remote[Int]) => n === 12) <-> true,
        Remote.some(12).forall((n: Remote[Int]) => n === 11) <-> false
      ),
      optionTest("get")(
        Remote.some(11).get <-> 11,
        Remote.none[Int].get failsWithRemoteError "get called on empty Option"
      ),
      optionTest("getOrElse")(
        Remote.some(11).getOrElse(12) <-> 11,
        Remote.none[Int].getOrElse(12) <-> 12
      ),
      optionTest("head")(
        Remote.some(11).head <-> 11,
        Remote.none[Int].head failsWithRemoteError "get called on empty Option"
      ),
      optionTest("headOption")(
        Remote.some(11).headOption <-> Some(11),
        Remote.none[Int].headOption <-> None
      ),
      optionTest("isSome")(
        Remote.none.isSome <-> false,
        Remote.some(12).isSome <-> true
      ),
      optionTest("isNone")(
        Remote.none.isNone <-> true,
        Remote.some(12).isNone <-> false
      ),
      optionTest("isDefined")(
        Remote.none.isDefined <-> false,
        Remote.some(12).isDefined <-> true
      ),
      optionTest("isEmpty")(
        Remote.none.isEmpty <-> true,
        Remote.some(12).isEmpty <-> false
      ),
      optionTest("knownSize")(
        Remote.none[Int].knownSize <-> 0,
        Remote.some(12).knownSize <-> 1
      ),
      optionTest("last")(
        Remote.some(11).last <-> 11,
        Remote.none[Int].last failsWithRemoteError "get called on empty Option"
      ),
      optionTest("lastOption")(
        Remote.some(11).lastOption <-> Some(11),
        Remote.none[Int].lastOption <-> None
      ),
      optionTest("map")(
        Remote.some(12).map(_ + 1) <-> Some(13),
        Remote.none[Int].map(_ + 1) <-> None
      ),
      optionTest("nonEmpty")(
        Remote.none.nonEmpty <-> false,
        Remote.some(12).nonEmpty <-> true
      ),
      optionTest("orElse")(
        Remote.none.orElse(Remote.some(2)) <-> Option(2),
        Remote.some(12).orElse(Remote.some(2)) <-> Option(12)
      ),
      optionTest("toLeft")(
        Remote.some(11).toLeft("x") <-> Left(11),
        Remote.none[Int].toLeft("x") <-> Right("x")
      ),
      optionTest("toList")(
        Remote.some(11).toList <-> List(11),
        Remote.none[Int].toList <-> List.empty[Int]
      ),
      optionTest("toRight")(
        Remote.some(11).toRight("x") <-> Right(11),
        Remote.none[Int].toRight("x") <-> Left("x")
      ),
      optionTest("zip")(
        Remote.none[Int].zip(Remote.some(10)) <-> None,
        Remote.some(10).zip(Remote.none[Int]) <-> None,
        Remote.some(12).zip(Remote.some(10)) <-> Some((12, 10))
      )
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
}
