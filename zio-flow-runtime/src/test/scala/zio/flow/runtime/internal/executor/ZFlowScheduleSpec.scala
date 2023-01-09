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

package zio.flow.runtime.internal.executor

import zio.flow._
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore}
import zio.test.{Spec, TestEnvironment, assertTrue}
import zio.{Duration, ZNothing, durationInt}

import java.time.ZoneOffset

object ZFlowScheduleSpec extends PersistentExecutorBaseSpec {
  override def flowSpec
    : Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore with Configuration, Any] =
    suite("ZFlowSchedule")(
      suite("schedule")(
        testFlowAndLogs("fixed", periodicAdjustClock = Some(30.seconds)) {
          ZFlow.now.flatMap { start =>
            ZFlow.log("event").schedule(ZFlowSchedule.fixed(Duration.ofMinutes(2L))) *>
              ZFlow.now.map(Duration.between(start, _).toMinutes)
          }
        } { (result, logs) =>
          assertTrue(
            result == 2L,
            logs.contains("event")
          )
        },
        testFlowAndLogs("forever", periodicAdjustClock = Some(30.seconds)) {
          ZFlow.now.flatMap { start =>
            ZFlow.log("event").schedule(ZFlowSchedule.forever) *>
              ZFlow.now.map(Duration.between(start, _).toSeconds)
          }
        } { (result, logs) =>
          assertTrue(
            result == 0L,
            logs.contains("event")
          )
        },
        testFlowAndLogs("fixed | fixed", periodicAdjustClock = Some(30.seconds)) {
          ZFlow.now.flatMap { start =>
            ZFlow
              .log("event")
              .schedule(
                ZFlowSchedule.fixed(Duration.ofMinutes(2L)) |
                  ZFlowSchedule.fixed(Duration.ofSeconds(30L))
              ) *>
              ZFlow.now.map(Duration.between(start, _).toSeconds)
          }
        } { (result, logs) =>
          assertTrue(
            result == 30L,
            logs.contains("event")
          )
        },
        testFlowAndLogs("everyHourAt", periodicAdjustClock = Some(30.minutes), gcPeriod = 30.days) {
          ZFlow.log("event").schedule(ZFlowSchedule.everyHourAt(12, 33)) *>
            ZFlow.now.map(OffsetDateTime.ofInstant(_, ZoneOffset.UTC))
        } { (result, logs) =>
          assertTrue(
            result.getMinute == 12,
            result.getSecond == 33,
            logs.contains("event")
          )
        },
        testFlowAndLogs("everyDayAt", periodicAdjustClock = Some(1.day), gcPeriod = 30.days) {
          ZFlow.log("event").schedule(ZFlowSchedule.everyDayAt(20, 12, 33)) *>
            ZFlow.now.map(OffsetDateTime.ofInstant(_, ZoneOffset.UTC))
        } { (result, logs) =>
          assertTrue(
            result.getHour == 20,
            result.getMinute == 12,
            result.getSecond == 33,
            logs.contains("event")
          )
        },
        testFlowAndLogs("everyMonthAt", periodicAdjustClock = Some(30.days), gcPeriod = 30.days) {
          ZFlow.log("event").schedule(ZFlowSchedule.everyMonthAt(7, 20, 12, 33)) *>
            ZFlow.now.map(OffsetDateTime.ofInstant(_, ZoneOffset.UTC))
        } { (result, logs) =>
          assertTrue(
            result.getDayOfMonth == 7,
            result.getHour == 20,
            result.getMinute == 12,
            result.getSecond == 33,
            logs.contains("event")
          )
        }
      ),
      suite("repeat")(
        testFlowAndLogs[ZNothing, List[Int]]("forever.maxCount", periodicAdjustClock = Some(30.seconds)) {
          ZFlow.log("event").as(1).repeat(ZFlowSchedule.forever.maxCount(5))
        } { (result, logs) =>
          assertTrue(
            result == List(1, 1, 1, 1, 1, 1),
            logs.count(_ == "event") == 6
          )
        },
        testFlowAndLogs("fixed.maxCount", periodicAdjustClock = Some(5.seconds)) {
          ZFlow.now.flatMap { start =>
            ZFlow.log("event").as(1).repeat(ZFlowSchedule.fixed(10.seconds).maxCount(2)) <*>
              ZFlow.now.map(Duration.between(start, _).toSeconds)
          }
        } { (result, logs) =>
          assertTrue(
            result._1 == List(1, 1, 1),
            result._2 == 20L,
            logs.count(_ == "event") == 3
          )
        }
      )
    )
}
