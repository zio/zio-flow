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

import zio.flow._
import zio.ZNothing
import zio.flow.Remote.EvaluatedRemoteFunction

class RemoteVariableReferenceSyntax[A](val self: Remote[RemoteVariableReference[A]]) extends AnyVal {

  /** Gets the value of a remote variable */
  def get: ZFlow[Any, ZNothing, A] =
    ZFlow.Read(self)

  /**
   * Updates the value stored in a remote variable using the given function, and
   * returns the old value.
   */
  def getAndUpdate(f: Remote[A] => Remote[A]): ZFlow[Any, ZNothing, A] =
    self.modify { (a: Remote[A]) =>
      val a2 = f(a)
      (a, a2)
    }

  /** Sets the value of a remote variable */
  def set(a: Remote[A]): ZFlow[Any, ZNothing, Unit] =
    self.modify((_: Remote[A]) => ((), a))

  /**
   * Modifies the value of a remote variable by the given function. The function
   * must return a pair of values, where the first value will be the result of
   * this flow, and the second value is the new value to be stored in the remote
   * variable.
   */
  def modify[B](
    f: Remote[A] => (Remote[B], Remote[A])
  ): ZFlow[Any, ZNothing, B] =
    ZFlow.Modify(self, EvaluatedRemoteFunction.make((a: Remote[A]) => Remote.tuple2(f(a))))

  /**
   * Updates the value stored in a remote variable using the given function, and
   * returns the new value.
   */
  def updateAndGet(f: Remote[A] => Remote[A]): ZFlow[Any, ZNothing, A] =
    self.modify { (a: Remote[A]) =>
      val a2 = f(a)
      (a2, a2)
    }

  /** Updates the value stored in a remote variable by the given function */
  def update(f: Remote[A] => Remote[A]): ZFlow[Any, ZNothing, Unit] =
    updateAndGet(f).unit

  /**
   * Wait until the remote variable changes to a value satisfying the given
   * predicate.
   *
   * If the predicate is already true, it returns immediately. Otherwise the
   * flow will be suspended until another forked flow changes its value.
   */
  def waitUntil(predicate: Remote[A] => Remote[Boolean]): ZFlow[Any, ZNothing, Unit] =
    ZFlow.transaction[Any, ZNothing, Unit] { txn =>
      self.get.flatMap[Any, ZNothing, Unit] { v =>
        txn.retryUntil(predicate(v))
      }
    }
}
