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

package zio.flow

sealed trait ZFlowTransaction {

  /**
   * Suspends the transaction until the variables in the transaction are
   * modified from the outside.
   */
  def retryUntil(predicate: Remote[Boolean]): ZFlow[Any, Nothing, Unit]
}

object ZFlowTransaction {
  private[flow] val instance: ZFlowTransaction =
    new ZFlowTransaction {
      def retryUntil(predicate: Remote[Boolean]): ZFlow[Any, Nothing, Unit] =
        ZFlow.ifThenElse[Any, Nothing, Unit](predicate)(ZFlow.unit, ZFlow.RetryUntil)
    }
}
