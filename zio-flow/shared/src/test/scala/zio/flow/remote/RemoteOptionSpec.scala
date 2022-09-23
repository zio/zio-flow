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

import zio.{ZIO, ZLayer}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{LocalContext, Remote, RemoteContext}
import zio.test.{Spec, TestEnvironment, TestResult}

object RemoteOptionSpec extends RemoteSpecBase {
  val suite1: Spec[TestEnvironment, Any] =
    suite("RemoteOptionSpec")(
      test("HandleOption for Some") {
        val option: Remote[Option[Int]] = Remote(Option(12))
        val optionHandled: Remote[Int]  = option.fold(Remote(0))((x: Remote[Int]) => x * 2)
        optionHandled <-> 24
      },
      test("HandleOption for None") {
        val option        = Remote[Option[Int]](None)
        val optionHandled = option.fold(Remote(0))((x: Remote[Int]) => x * 2)
        optionHandled <-> 0
      },
      test("isSome") {
        val op1 = Remote.none
        val op2 = Remote.some(12)
        ZIO
          .collectAll(
            List(
              op1.isSome <-> false,
              op2.isSome <-> true
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("isNone") {
        val op1 = Remote.none
        val op2 = Remote.some(12)
        ZIO
          .collectAll(
            List(
              op1.isNone <-> true,
              op2.isNone <-> false
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("isEmpty") {
        val op1 = Remote.none
        val op2 = Remote.some(12)
        ZIO
          .collectAll(
            List(
              op1.isEmpty <-> true,
              op2.isEmpty <-> false
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("isDefined") {
        val op1 = Remote.none
        val op2 = Remote.some(12)
        ZIO
          .collectAll(
            List(
              op1.isDefined <-> false,
              op2.isDefined <-> true
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("knownSize") {
        val op1 = Remote.none[Int]
        val op2 = Remote.some(12)
        ZIO
          .collectAll(
            List(
              op1.knownSize <-> 0,
              op2.knownSize <-> 1
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("contains") {
        val op1 = Remote.none
        val op2 = Remote.some(12)
        ZIO
          .collectAll(
            List(
              op1.contains(2) <-> false,
              op2.contains(12) <-> true,
              op2.contains(11) <-> false
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("orElse") {
        val op1 = Remote.none
        val op2 = Remote.some(12)
        ZIO
          .collectAll(
            List(
              op1.orElse(Option(2)) <-> Option(2),
              op2.orElse(Option(2)) <-> Option(12)
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("zip") {
        val op1 = Remote.none[Int]
        val op2 = Remote.some(12)
        val op3 = Remote.some(10)
        ZIO
          .collectAll(
            List(
              op1.zip(op3) <-> None,
              op3.zip(op1) <-> None,
              op2.zip(op3) <-> Some((12, 10))
            )
          )
          .map(TestResult.all(_: _*))
      }
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

  override def spec = suite("OptionSpec")(suite1)
}
