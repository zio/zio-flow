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

import zio.ZNothing
import zio.schema.{Schema, TypeId}

/**
 * Activity is a special step for a zio-flow workflow capable of communicating
 * with the external world via an [[Operation]].
 *
 * When calling an activity from a workflow, a value of type Input must be
 * provided, and the result of successfully executing an activity has the type
 * Result.
 *
 * An activity can be reverted when it is being called from a transaction. If
 * the transaction get reverted (due to a failure or a retry) the compensate
 * flow can perform an arbitrary number of steps necessary to cancel whatever
 * the activity has done. If this is not supported, use
 * Activity.compensateNotSupported.
 *
 * The check flow can be used to perform steps verifying if the activity still
 * needed to be executed. In case it was restarted by a transaction or because
 * the whole workflow executor got restarted, this feature can be used to make
 * sure the operation was only executed exactly once. If this is not supported,
 * use Activity.checkNotSupported.
 */
final case class Activity[-Input, Result](
  name: String,
  description: String,
  operation: Operation[Input, Result],
  check: ZFlow[Input, ActivityError, Result],
  compensate: ZFlow[Result, ActivityError, Unit]
) { self =>
  val inputSchema: Schema[_ >: Input]   = operation.inputSchema
  val resultSchema: Schema[_ <: Result] = operation.resultSchema

  /** Execute this activity with the given input */
  def apply(input: Remote[Input]): ZFlow[Any, ActivityError, Result] =
    ZFlow.RunActivity(input, self)

  /** Execute this activity with the given inputs */
  def apply[I1, I2](input1: Remote[I1], input2: Remote[I2])(implicit
    ev: (I1, I2) <:< Input
  ): ZFlow[Any, ActivityError, Result] =
    self.narrow[(I1, I2)].apply(Remote.tuple2((input1, input2)))

  /** Execute this activity with the given inputs */
  def apply[I1, I2, I3](input1: Remote[I1], input2: Remote[I2], input3: Remote[I3])(implicit
    ev: (I1, I2, I3) <:< Input
  ): ZFlow[Any, ActivityError, Result] =
    self.narrow[(I1, I2, I3)].apply(Remote.tuple3((input1, input2, input3)))

  /** Execute this activity with the given inputs */
  def apply[I1, I2, I3, I4](input1: Remote[I1], input2: Remote[I2], input3: Remote[I3], input4: Remote[I4])(implicit
    ev: (I1, I2, I3, I4) <:< Input
  ): ZFlow[Any, ActivityError, Result] =
    self.narrow[(I1, I2, I3, I4)].apply(Remote.tuple4((input1, input2, input3, input4)))

  /** Execute this activity with the given inputs */
  def apply[I1, I2, I3, I4, I5](
    input1: Remote[I1],
    input2: Remote[I2],
    input3: Remote[I3],
    input4: Remote[I4],
    input5: Remote[I5]
  )(implicit
    ev: (I1, I2, I3, I4, I5) <:< Input
  ): ZFlow[Any, ActivityError, Result] =
    self.narrow[(I1, I2, I3, I4, I5)].apply(Remote.tuple5((input1, input2, input3, input4, input5)))

  /** Execute this activity with the given inputs */
  def apply[I1, I2, I3, I4, I5, I6](
    input1: Remote[I1],
    input2: Remote[I2],
    input3: Remote[I3],
    input4: Remote[I4],
    input5: Remote[I5],
    input6: Remote[I6]
  )(implicit
    ev: (I1, I2, I3, I4, I5, I6) <:< Input
  ): ZFlow[Any, ActivityError, Result] =
    self.narrow[(I1, I2, I3, I4, I5, I6)].apply(Remote.tuple6((input1, input2, input3, input4, input5, input6)))

  /**
   * Defines an activity that performs the same operations but its input is
   * transformed by the given function.
   */
  def contramap[Input2: Schema](
    from: Remote[Input2] => Remote[Input]
  ): Activity[Input2, Result] =
    this.copy(
      operation = operation.contramap(from),
      check = ZFlow.input[Input2].flatMap(input2 => from(input2)).flatMap(input => check.provide(input))
    )

  /**
   * Executes this activity by requiring its input to be the flow's input.
   */
  def fromInput: ZFlow[Input, ActivityError, Result] =
    ZFlow.input[Input].flatMap(apply)

  /**
   * Defines an activity that performs the same operations but its result is
   * transformed by the given function 'to'.
   *
   * Another function 'from' must also be specified which is used to convert
   * back the result in order to pass it to the original 'compensate' flow.
   */
  def mapResult[Result2: Schema](
    to: Remote[Result] => Remote[Result2],
    from: Remote[Result2] => Remote[Result]
  ): Activity[Input, Result2] =
    this.copy(
      operation = operation.map(to),
      check = check.map(to),
      compensate = ZFlow.input[Result2].flatMap(result2 => from(result2)).flatMap(result => compensate.provide(result))
    )

  /** Narrows the input type of this activity */
  def narrow[Input0](implicit ev: Input0 <:< Input): Activity[Input0, Result] = {
    val _ = ev

    self.asInstanceOf[Activity[Input0, Result]]
  }
}

object Activity {

  /**
   * A default implementation of a check for activities not supporting checking
   * their status
   */
  val checkNotSupported: ZFlow[Any, ActivityError, Nothing] =
    ZFlow.fail(ActivityError("Check is not supported for this Activity", None))

  /**
   * A default implementation of a compensate flow for activities not supporting
   * reverting themselves
   */
  val compensateNotSupported: ZFlow[Any, ZNothing, Unit] =
    ZFlow.unit

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
