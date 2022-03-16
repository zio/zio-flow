///*
// * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package zio.flow.remote
//
//import zio.flow._
//
//import java.time.Duration
//import java.time.temporal.{Temporal, TemporalAmount, TemporalUnit}
//
//class RemoteDurationSyntax(val self: Remote[Duration]) extends AnyVal {
//
//  def isZero: Remote[Boolean]     = self.getSeconds === 0L && self.getNano === 0L
//  def isNegative: Remote[Boolean] = self.getSeconds < 0L
//  def getSeconds: Remote[Long]    = Remote.DurationToLongs(self.widen[Duration])._1
//  def getNano: Remote[Long]       = Remote.DurationToLongs(self.widen[Duration])._2
//
//  def plus(that: Remote[Duration]): Remote[Duration] =
//    Remote.DurationPlusDuration(self, that)
//
//  def plus(amountToAdd: Remote[Long], temporalUnit: Remote[TemporalUnit]): Remote[Duration] =
//    plus(Remote.DurationFromAmount(amountToAdd, temporalUnit))
//
//  def plusDays(daysToAdd: Remote[Long]): Remote[Duration]       = plus(Remote.ofDays(daysToAdd))
//  def plusHours(hoursToAdd: Remote[Long]): Remote[Duration]     = plus(Remote.ofHours(hoursToAdd))
//  def plusMinutes(minutesToAdd: Remote[Long]): Remote[Duration] = plus(Remote.ofMinutes(minutesToAdd))
//  def plusSeconds(secondsToAdd: Remote[Long]): Remote[Duration] = plus(Remote.ofSeconds(secondsToAdd))
//  def plusNanos(nanoToAdd: Remote[Long]): Remote[Duration]      = plus(Remote.ofNanos(nanoToAdd))
//
//  def minus(that: Remote[Duration]): Remote[Duration] =
//    Remote.DurationMinusDuration(self, that)
//
//  def minus(amountToSubtract: Remote[Long], temporalUnit: Remote[TemporalUnit]): Remote[Duration] =
//    minus(Remote.DurationFromAmount(amountToSubtract, temporalUnit))
//
//  def minusDays(daysToSubtract: Remote[Long]): Remote[Duration]       = minus(Remote.ofDays(daysToSubtract))
//  def minusHours(hoursToSubtract: Remote[Long]): Remote[Duration]     = minus(Remote.ofHours(hoursToSubtract))
//  def minusMinutes(minutesToSubtract: Remote[Long]): Remote[Duration] = minus(Remote.ofMinutes(minutesToSubtract))
//  def minusSeconds(secondsToSubtract: Remote[Long]): Remote[Duration] = minus(Remote.ofSeconds(secondsToSubtract))
//  def minusNanos(nanosToSubtract: Remote[Long]): Remote[Duration]     = minus(Remote.ofNanos(nanosToSubtract))
//}
//
//object RemoteDuration {
//
//  def from(amount: Remote[TemporalAmount]): Remote[Duration] =
//    Remote.DurationFromTemporalAmount(amount)
//
//  def parse(charSequence: Remote[String]): Remote[Duration] =
//    Remote.DurationFromString(charSequence)
//
//  def create(seconds: Remote[java.math.BigDecimal]): Remote[Duration] =
//    Remote.DurationFromBigDecimal(seconds)
//
//  def between(startInclusive: Remote[Temporal], endExclusive: Remote[Temporal]): Remote[Duration] =
//    Remote.DurationFromTemporals(startInclusive, endExclusive)
//}
