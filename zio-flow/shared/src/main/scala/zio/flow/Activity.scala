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

final case class Activity[-R, A](
  name: String,
  description: String,
  operation: Operation[R, A],
  check: ZFlow[R, ActivityError, A],
  compensate: ZFlow[A, ActivityError, Any]
) { self =>
  def apply(input: Remote[R])(implicit schema: SchemaOrNothing.Aux[A]): ZFlow[Any, ActivityError, A] =
    ZFlow.RunActivity(input, self)

  def apply[R1, R2](R1: Remote[R1], R2: Remote[R2])(implicit
    ev: (R1, R2) <:< R,
    schema: SchemaOrNothing.Aux[A]
  ): ZFlow[Any, ActivityError, A] =
    self.narrow[(R1, R2)].apply(Remote.tuple2((R1, R2)))

  def apply[R1, R2, I3](R1: Remote[R1], R2: Remote[R2], i3: Remote[I3])(implicit
    ev: (R1, R2, I3) <:< R,
    schema: SchemaOrNothing.Aux[A]
  ): ZFlow[Any, ActivityError, A] =
    self.narrow[(R1, R2, I3)].apply(Remote.tuple3((R1, R2, i3)))

  def apply[R1, R2, I3, I4](R1: Remote[R1], R2: Remote[R2], i3: Remote[I3], i4: Remote[I4])(implicit
    ev: (R1, R2, I3, I4) <:< R,
    schema: SchemaOrNothing.Aux[A]
  ): ZFlow[Any, ActivityError, A] =
    self.narrow[(R1, R2, I3, I4)].apply(Remote.tuple4((R1, R2, i3, i4)))

  def narrow[R0](implicit ev: R0 <:< R): Activity[R0, A] = {
    val _ = ev

    self.asInstanceOf[Activity[R0, A]]
  }
}
object Activity {}
