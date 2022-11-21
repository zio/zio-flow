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

import zio.Duration

import java.time.temporal.ChronoUnit

trait ZFlowSchedule[Ctx] { self =>
  def init: ZFlow[Any, Nothing, Ctx]
  def next(ctx: Remote[Ctx]): ZFlow[Any, Nothing, Option[Instant]]

  def maxCount(count: Int): ZFlowSchedule[(Ctx, RemoteVariableReference[Int])] =
    new ZFlowSchedule[(Ctx, RemoteVariableReference[Int])] {
      override def init: ZFlow[Any, Nothing, (Ctx, RemoteVariableReference[Int])] =
        self.init.flatMap { left =>
          ZFlow.newTempVar("schedule_remaining", count).map { right =>
            (left, right)
          }
        }

      override def next(ctx: Remote[(Ctx, RemoteVariableReference[Int])]): ZFlow[Any, Nothing, Option[Instant]] =
        ctx._2.get.flatMap { remaining =>
          ZFlow.unwrap {
            (remaining > 0).ifThenElse(
              ifTrue = ctx._2.set(remaining - 1) *> self.next(ctx._1),
              ifFalse = ZFlow.succeed(Remote.none[Instant])
            )
          }
        }
    }
}

object ZFlowSchedule {
  def everyHourAt(minute: Remote[Int], second: Remote[Int]): ZFlowSchedule[Unit] =
    new ZFlowSchedule[Unit] {
      override def init: ZFlow[Any, Nothing, Unit] = ZFlow.unit
      override def next(ctx: Remote[Unit]): ZFlow[Any, Nothing, Option[Instant]] =
        ZFlow.now.map(now =>
          Remote.bind(now.truncatedTo(ChronoUnit.HOURS).plusSeconds((minute.toLong * 60L) + second.toLong)) {
            inThisHour =>
              inThisHour
                .isAfter(now)
                .ifThenElse(
                  ifTrue = Remote.some(inThisHour),
                  ifFalse = Remote.some(inThisHour.plus(1L, ChronoUnit.HOURS))
                )
          }
        )
    }

  def fixed(interval: Remote[Duration]): ZFlowSchedule[Unit] =
    new ZFlowSchedule[Unit] {
      def init: ZFlow[Any, Nothing, Unit] = ZFlow.unit
      def next(ctx: Remote[Unit]): ZFlow[Any, Nothing, Option[Instant]] =
        ZFlow.now.map(now => Remote.some(now.plus(interval)))
    }

  def forever: ZFlowSchedule[Unit] =
    new ZFlowSchedule[Unit] {
      override def init: ZFlow[Any, Nothing, Unit] =
        ZFlow.unit

      override def next(ctx: Remote[Unit]): ZFlow[Any, Nothing, Option[Instant]] =
        ZFlow.now.map(Remote.some)
    }
}
