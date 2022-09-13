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

import zio.ZLayer
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow._
import zio.test.{Spec, TestEnvironment}

object RemoteListSpec extends RemoteSpecBase {
  val suite1: Spec[TestEnvironment, Nothing] =
    suite("RemoteListSpec")(
      test("Reverse") {
        val remoteList   = Remote(1 :: 2 :: 3 :: 4 :: Nil)
        val reversedList = remoteList.reverse
        reversedList <-> (4 :: 3 :: 2 :: 1 :: Nil)
      },
      test("Reverse empty list") {
        val reversedEmptyList = Remote[List[Int]](Nil).reverse
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
        val l2       = Remote[List[Int]](Nil)
        val appended = l1 ++ l2
        appended <-> (1 :: 2 :: 3 :: 4 :: 5 :: Nil)
      },
      test("Cons") {
        val l1   = Remote(1 :: 2 :: 3 :: Nil)
        val cons = Remote.Cons(l1, Remote(4))
        cons <-> (4 :: 1 :: 2 :: 3 :: Nil)
      },
      test("UnCons") {
        val l1     = Remote(1 :: 2 :: 3 :: Nil)
        val uncons = Remote.UnCons(l1)
        uncons <-> Some((1, 2 :: 3 :: Nil))
      },
      test("Fold") {
        val l1                = Remote(1 :: 2 :: 3 :: Nil)
        val fold: Remote[Int] = l1.foldLeft(Remote(0))((a, b) => a + b)
        fold <-> 6
      }
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

  override def spec = suite("RemoteListSpec")(suite1)
}
