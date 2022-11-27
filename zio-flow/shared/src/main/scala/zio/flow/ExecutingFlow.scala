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

package zio.flow

import zio.schema.{Schema, TypeId}

final case class ExecutingFlow[+E, +A](id: FlowId, result: PromiseId)

object ExecutingFlow {
  private val typeId: TypeId = TypeId.parse("zio.flow.ExecutingFlow")

  implicit def schema[E, A]: Schema[ExecutingFlow[E, A]] =
    Schema.CaseClass2[FlowId, PromiseId, ExecutingFlow[E, A]](
      typeId,
      Schema.Field("id", Schema[FlowId], get0 = _.id, set0 = (a: ExecutingFlow[E, A], v: FlowId) => a.copy(id = v)),
      Schema.Field(
        "result",
        Schema[PromiseId],
        get0 = _.result,
        set0 = (a: ExecutingFlow[E, A], v: PromiseId) => a.copy(result = v)
      ),
      { case (id, promise) => ExecutingFlow(id, promise) }
    )
}
