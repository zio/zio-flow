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

import zio.flow._
import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test.{Spec, TestEnvironment}
import zio.{Scope, ZLayer}

object RemoteOptionSyntaxSpec extends RemoteSpecBase {

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("RemoteOptionSyntax")(
      remoteTest("contains")(
        Remote.none.contains(2) <-> false,
        Remote.some(12).contains(12) <-> true,
        Remote.some(12).contains(11) <-> false
      ),
      remoteTest("exists")(
        Remote.none[Int].exists((n: Remote[Int]) => n === 12) <-> false,
        Remote.some(12).exists((n: Remote[Int]) => n === 12) <-> true,
        Remote.some(12).exists((n: Remote[Int]) => n === 11) <-> false
      ),
      remoteTest("filter")(
        Remote.none[Int].filter((n: Remote[Int]) => n === 12) <-> None,
        Remote.some(12).filter((n: Remote[Int]) => n === 12) <-> Some(12),
        Remote.some(12).filter((n: Remote[Int]) => n === 11) <-> None
      ),
      remoteTest("filterNot")(
        Remote.none[Int].filterNot((n: Remote[Int]) => n === 12) <-> None,
        Remote.some(12).filterNot((n: Remote[Int]) => n === 12) <-> None,
        Remote.some(12).filterNot((n: Remote[Int]) => n === 11) <-> Some(12)
      ),
      remoteTest("flatMap")(
        Remote.some(12).flatMap((n: Remote[Int]) => Remote.some(n + 1)) <-> Some(13),
        Remote.none[Int].flatMap((n: Remote[Int]) => Remote.some(n + 1)) <-> None,
        Remote.some(12).flatMap((_: Remote[Int]) => Remote.none[Int]) <-> None
      ),
      remoteTest("fold")(
        Remote.some(12).fold(Remote(0))((x: Remote[Int]) => x * 2) <-> 24,
        Remote.none[Int].fold(Remote(0))((x: Remote[Int]) => x * 2) <-> 0
      ),
      remoteTest("foldLeft")(
        Remote.some(12).foldLeft(Remote(1))(t => t._1 + t._2) <-> 13,
        Remote.none[Int].foldLeft(Remote(1))(t => t._1 + t._2) <-> 1
      ),
      remoteTest("foldRight")(
        Remote.some(12).foldRight(Remote(1))(t => t._1 + t._2) <-> 13,
        Remote.none[Int].foldRight(Remote(1))(t => t._1 + t._2) <-> 1
      ),
      remoteTest("forall")(
        Remote.none[Int].forall((n: Remote[Int]) => n === 12) <-> false,
        Remote.some(12).forall((n: Remote[Int]) => n === 12) <-> true,
        Remote.some(12).forall((n: Remote[Int]) => n === 11) <-> false
      ),
      remoteTest("get")(
        Remote.some(11).get <-> 11,
        Remote.none[Int].get failsWithRemoteFailure "get called on empty Option"
      ),
      remoteTest("getOrElse")(
        Remote.some(11).getOrElse(12) <-> 11,
        Remote.none[Int].getOrElse(12) <-> 12
      ),
      remoteTest("head")(
        Remote.some(11).head <-> 11,
        Remote.none[Int].head failsWithRemoteFailure "get called on empty Option"
      ),
      remoteTest("headOption")(
        Remote.some(11).headOption <-> Some(11),
        Remote.none[Int].headOption <-> None
      ),
      remoteTest("isSome")(
        Remote.none.isSome <-> false,
        Remote.some(12).isSome <-> true
      ),
      remoteTest("isNone")(
        Remote.none.isNone <-> true,
        Remote.some(12).isNone <-> false
      ),
      remoteTest("isDefined")(
        Remote.none.isDefined <-> false,
        Remote.some(12).isDefined <-> true
      ),
      remoteTest("isEmpty")(
        Remote.none.isEmpty <-> true,
        Remote.some(12).isEmpty <-> false
      ),
      remoteTest("knownSize")(
        Remote.none[Int].knownSize <-> 0,
        Remote.some(12).knownSize <-> 1
      ),
      remoteTest("last")(
        Remote.some(11).last <-> 11,
        Remote.none[Int].last failsWithRemoteFailure "get called on empty Option"
      ),
      remoteTest("lastOption")(
        Remote.some(11).lastOption <-> Some(11),
        Remote.none[Int].lastOption <-> None
      ),
      remoteTest("map")(
        Remote.some(12).map(_ + 1) <-> Some(13),
        Remote.none[Int].map(_ + 1) <-> None
      ),
      remoteTest("nonEmpty")(
        Remote.none.nonEmpty <-> false,
        Remote.some(12).nonEmpty <-> true
      ),
      remoteTest("orElse")(
        Remote.none.orElse(Remote.some(2)) <-> Option(2),
        Remote.some(12).orElse(Remote.some(2)) <-> Option(12)
      ),
      remoteTest("toLeft")(
        Remote.some(11).toLeft("x") <-> Left(11),
        Remote.none[Int].toLeft("x") <-> Right("x")
      ),
      remoteTest("toList")(
        Remote.some(11).toList <-> List(11),
        Remote.none[Int].toList <-> List.empty[Int]
      ),
      remoteTest("toRight")(
        Remote.some(11).toRight("x") <-> Right(11),
        Remote.none[Int].toRight("x") <-> Left("x")
      ),
      remoteTest("zip")(
        Remote.none[Int].zip(Remote.some(10)) <-> None,
        Remote.some(10).zip(Remote.none[Int]) <-> None,
        Remote.some(12).zip(Remote.some(10)) <-> Some((12, 10))
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
}
