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

final case class Activity[-Input, Result](
  name: String,
  description: String,
  operation: Operation[Input, Result],
  check: ZFlow[Input, ActivityError, Result],
  compensate: ZFlow[Result, ActivityError, Unit]
) { self =>
  val inputSchema: Schema[_ >: Input]   = operation.inputSchema
  val resultSchema: Schema[_ <: Result] = operation.resultSchema

  def apply(input: Remote[Input]): ZFlow[Any, ActivityError, Result] =
    ZFlow.RunActivity(input, self)

  def apply[R1, R2](R1: Remote[R1], R2: Remote[R2])(implicit
    ev: (R1, R2) <:< Input
  ): ZFlow[Any, ActivityError, Result] =
    self.narrow[(R1, R2)].apply(Remote.tuple2((R1, R2)))

  def apply[R1, R2, I3](R1: Remote[R1], R2: Remote[R2], i3: Remote[I3])(implicit
    ev: (R1, R2, I3) <:< Input
  ): ZFlow[Any, ActivityError, Result] =
    self.narrow[(R1, R2, I3)].apply(Remote.tuple3((R1, R2, i3)))

  def apply[R1, R2, I3, I4](R1: Remote[R1], R2: Remote[R2], i3: Remote[I3], i4: Remote[I4])(implicit
    ev: (R1, R2, I3, I4) <:< Input
  ): ZFlow[Any, ActivityError, Result] =
    self.narrow[(R1, R2, I3, I4)].apply(Remote.tuple4((R1, R2, i3, i4)))

  def contramap[Input2: Schema](
    from: Remote[Input2] => Remote[Input]
  ): Activity[Input2, Result] =
    this.copy(
      operation = operation.contramap(from),
      check = ZFlow.input[Input2].flatMap(input2 => from(input2)).flatMap(input => check.provide(input))
    )

  def fromInput: ZFlow[Input, ActivityError, Result] =
    ZFlow.input[Input].flatMap(apply)

  def narrow[Input0](implicit ev: Input0 <:< Input): Activity[Input0, Result] = {
    val _ = ev

    self.asInstanceOf[Activity[Input0, Result]]
  }
}

object Activity {
  val checkNotSupported: ZFlow[Any, ActivityError, Nothing] =
    ZFlow.fail(ActivityError("Check is not supported for this Activity", None))
  val compensateNotSupported: ZFlow[Any, ActivityError, Nothing] =
    ZFlow.fail(ActivityError("Compensate is not supported for this Activity", None))

  private val typeId: TypeId = TypeId.parse("zio.flow.Activity")

  def schema[R, A]: Schema[Activity[R, A]] =
    Schema.CaseClass5[String, String, Operation[R, A], ZFlow[R, ActivityError, A], ZFlow[
      A,
      ActivityError,
      Unit
    ], Activity[R, A]](
      typeId,
      Schema.Field("name", Schema[String]),
      Schema.Field("description", Schema[String]),
      Schema.Field("operation", Operation.schema[R, A]),
      Schema.Field("check", ZFlow.schema[R, ActivityError, A]),
      Schema.Field("compensate", ZFlow.schema[A, ActivityError, Unit]),
      { case (name, description, operation, check, compensate) =>
        Activity(
          name,
          description,
          operation,
          check,
          compensate
        )
      },
      _.name,
      _.description,
      _.operation,
      _.check,
      _.compensate
    )
}
