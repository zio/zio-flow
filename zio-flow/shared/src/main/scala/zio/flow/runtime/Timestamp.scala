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

package zio.flow.runtime

import zio.schema.Schema

case class Timestamp(value: Long) {
  def <(other: Timestamp): Boolean     = value < other.value
  def <=(other: Timestamp): Boolean    = value <= other.value
  def >(other: Timestamp): Boolean     = value > other.value
  def >=(other: Timestamp): Boolean    = value >= other.value
  def max(other: Timestamp): Timestamp = Timestamp(Math.max(value, other.value))

  def next: Timestamp = Timestamp(value + 1)
}

object Timestamp {
  implicit val schema: Schema[Timestamp] = Schema[Long].transform(
    Timestamp(_),
    _.value
  )

  implicit val ordering: Ordering[Timestamp] = (x: Timestamp, y: Timestamp) => x.value.compareTo(y.value)
}
