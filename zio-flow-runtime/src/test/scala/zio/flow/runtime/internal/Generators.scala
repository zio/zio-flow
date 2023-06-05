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

package zio.flow.runtime.internal

import zio.flow.{FlowId, RemoteVariableName, TransactionId}
import zio.test.{Gen, Sized}

object Generators {
  lazy val genRemoteVariableName: Gen[Sized, RemoteVariableName] =
    Gen.string1(Gen.alphaNumericChar).map(RemoteVariableName.unsafeMake)

  lazy val genFlowId: Gen[Sized, FlowId] =
    Gen.alphaNumericStringBounded(1, 16).map(FlowId.unsafeMake)

  lazy val genTransactionId: Gen[Sized, TransactionId] =
    Gen.alphaNumericStringBounded(1, 16).map(TransactionId.unsafeMake)

  lazy val genScope: Gen[Sized, RemoteVariableScope] =
    Gen.suspend {
      Gen.oneOf(
        genFlowId.map(RemoteVariableScope.TopLevel.apply),
        (genFlowId <*> genScope).map { case (id, scope) => RemoteVariableScope.Fiber(id, scope) },
        (genTransactionId <*> genScope).map { case (id, scope) => RemoteVariableScope.Transactional(scope, id) }
      )
    }

  lazy val genScopedRemoteVariableName: Gen[Sized, ScopedRemoteVariableName] =
    (genRemoteVariableName <*> genScope).map { case (name, scope) => ScopedRemoteVariableName(name, scope) }

}
