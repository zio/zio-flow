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

package zio.flow.remote

import zio.flow._

import java.time.ZoneOffset

final class RemoteOffsetDateTimeCompanionSyntax(val instant: OffsetDateTime.type) extends AnyVal {

  def of(
    year: Remote[Int],
    month: Remote[Int],
    dayOfMonth: Remote[Int],
    hour: Remote[Int],
    minute: Remote[Int],
    second: Remote[Int],
    nanoOfSecond: Remote[Int],
    offset: Remote[ZoneOffset]
  ): Remote[OffsetDateTime] =
    Remote.Unary(
      Remote.tuple8((year, month, dayOfMonth, hour, minute, second, nanoOfSecond, offset)),
      UnaryOperators.Conversion(RemoteConversions.TupleToOffsetDateTime)
    )

  def ofInstant(instant: Remote[Instant], offset: Remote[ZoneOffset]): Remote[OffsetDateTime] =
    Remote.Binary(instant, offset, BinaryOperators.InstantToOffsetDateTime)
}
