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

import java.time.ZoneOffset

final class RemoteOffsetDateTimeSyntax(val self: Remote[OffsetDateTime]) extends AnyVal {

  def getYear: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToTuple))._1

  def getMonthValue: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToTuple))._2

  def getDayOfMonth: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToTuple))._3

  def getHour: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToTuple))._4

  def getMinute: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToTuple))._5

  def getSecond: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToTuple))._6

  def getNano: Remote[Int] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToTuple))._7

  def getOffset: Remote[ZoneOffset] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToTuple))._8

  def toInstant: Remote[Instant] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.OffsetDateTimeToInstant))
}
