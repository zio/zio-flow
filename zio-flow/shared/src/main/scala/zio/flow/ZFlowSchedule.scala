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

import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Represents a schedule for running a workflow in the future */
trait ZFlowSchedule[Ctx] { self =>
  def init: ZFlow[Any, Nothing, Ctx]
  def next(ctx: Remote[Ctx]): ZFlow[Any, Nothing, Option[Instant]]

  /** Limits this schedule to be executed the given number of times */
  def maxCount(count: Remote[Int]): ZFlowSchedule[(Ctx, RemoteVariableReference[Int])] =
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

  /** Symbolic alias for [[or]] */
  def |[Ctx2](that: ZFlowSchedule[Ctx2]): ZFlowSchedule[(Ctx, Ctx2)] =
    self.or(that)

  /**
   * Combines this schedule with another schedule in a way that the one that the
   * scheduled action will run according to the schedule that is triggering
   * first
   */
  def or[Ctx2](that: ZFlowSchedule[Ctx2]): ZFlowSchedule[(Ctx, Ctx2)] =
    new ZFlowSchedule[(Ctx, Ctx2)] {
      override def init: ZFlow[Any, Nothing, (Ctx, Ctx2)] =
        self.init.zip(that.init)

      override def next(ctx: Remote[(Ctx, Ctx2)]): ZFlow[Any, Nothing, Option[Instant]] =
        self.next(ctx._1).zip(that.next(ctx._2)).map { pair =>
          pair._1.fold(pair._2)(leftNext =>
            pair._2.fold(Remote.some(leftNext))(rightNext =>
              Remote.some(leftNext.isBefore(rightNext).ifThenElse(ifTrue = leftNext, ifFalse = rightNext))
            )
          )
        }
    }
}

object ZFlowSchedule {

  /** Schedule to run in every hour at the given minute and second */
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

  /** Schedule to ru nevery day at the given hour, minute and second */
  def everyDayAt(hour: Remote[Int], minute: Remote[Int], second: Remote[Int]): ZFlowSchedule[Unit] =
    new ZFlowSchedule[Unit] {
      override def init: ZFlow[Any, Nothing, Unit] = ZFlow.unit

      override def next(ctx: Remote[Unit]): ZFlow[Any, Nothing, Option[Instant]] =
        ZFlow.now.map { now =>
          Remote.bind(OffsetDateTime.ofInstant(now, Remote(ZoneOffset.UTC))) { nowDateTime =>
            Remote.bind(
              OffsetDateTime
                .of(
                  nowDateTime.getYear,
                  nowDateTime.getMonthValue,
                  nowDateTime.getDayOfMonth,
                  hour,
                  minute,
                  second,
                  0,
                  ZoneOffset.UTC
                )
                .toInstant
            ) { inThisDay =>
              inThisDay
                .isAfter(now)
                .ifThenElse(
                  ifTrue = Remote.some(inThisDay),
                  ifFalse = Remote.some(inThisDay.plus(1L, ChronoUnit.DAYS))
                )
            }
          }
        }
    }

  /** Schedule to run in every month on the given day at the given time */
  def everyMonthAt(
    dayOfMonth: Remote[Int],
    hour: Remote[Int],
    minute: Remote[Int],
    second: Remote[Int]
  ): ZFlowSchedule[Unit] =
    new ZFlowSchedule[Unit] {
      override def init: ZFlow[Any, Nothing, Unit] = ZFlow.unit

      override def next(ctx: Remote[Unit]): ZFlow[Any, Nothing, Option[Instant]] =
        ZFlow.now.map { now =>
          Remote.bind(OffsetDateTime.ofInstant(now, Remote(ZoneOffset.UTC))) { nowDateTime =>
            Remote.bind(
              OffsetDateTime
                .of(
                  nowDateTime.getYear,
                  nowDateTime.getMonthValue,
                  dayOfMonth,
                  hour,
                  minute,
                  second,
                  0,
                  ZoneOffset.UTC
                )
                .toInstant
            ) { inThisMonth =>
              inThisMonth
                .isAfter(now)
                .ifThenElse(
                  ifTrue = Remote.some(inThisMonth),
                  ifFalse = Remote.bind(
                    OffsetDateTime.ofInstant(inThisMonth.plus(1L, ChronoUnit.MONTHS), Remote(ZoneOffset.UTC))
                  ) { inNextMonth =>
                    Remote.some(
                      OffsetDateTime
                        .of(
                          inNextMonth.getYear,
                          inNextMonth.getMonthValue,
                          dayOfMonth,
                          hour,
                          minute,
                          second,
                          0,
                          ZoneOffset.UTC
                        )
                        .toInstant
                    )
                  }
                )
            }
          }
        }
    }

  /** Schedule to run in fixed intervals */
  def fixed(interval: Remote[Duration]): ZFlowSchedule[Unit] =
    new ZFlowSchedule[Unit] {
      def init: ZFlow[Any, Nothing, Unit] = ZFlow.unit
      def next(ctx: Remote[Unit]): ZFlow[Any, Nothing, Option[Instant]] =
        ZFlow.now.map(now => Remote.some(now.plus(interval)))
    }

  /** Schedule to run without condition or delay */
  def forever: ZFlowSchedule[Unit] =
    new ZFlowSchedule[Unit] {
      override def init: ZFlow[Any, Nothing, Unit] =
        ZFlow.unit

      override def next(ctx: Remote[Unit]): ZFlow[Any, Nothing, Option[Instant]] =
        ZFlow.now.map(Remote.some)
    }
}
