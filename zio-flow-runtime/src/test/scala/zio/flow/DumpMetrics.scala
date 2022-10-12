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

import zio.internal.metrics.metricRegistry
import zio.metrics.{MetricKey, MetricLabel, MetricPair, MetricState}
import zio.{Console, ZIO, ZLayer}

import java.io.IOException
import scala.{Console => SC}

object DumpMetrics {
  val layer: ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped {
      for {
        _ <- ZIO.addFinalizer {
               ZIO.attemptUnsafe { implicit u =>
                 metricRegistry.snapshot()
               }.flatMap(snapshot => dumpMetrics(snapshot).when(snapshot.nonEmpty)).orDie
             }
      } yield ()
    }

  private def dumpMetrics(metrics: Set[MetricPair.Untyped]): ZIO[Any, IOException, Unit] =
    for {
      _                  <- printHeader("Collected metrics")
      byName              = metrics.groupBy(_.metricKey.name).toList.sortBy(_._1)
      maxNameLength       = metrics.map(_.metricKey.name.length).max + 2
      maxTagSectionLength = metrics.map(m => tagsToString(m.metricKey.tags, useColors = false).length).max + 2
      _ <- ZIO.foreachDiscard(byName) { case (_, metrics) =>
             ZIO.foreachDiscard(metrics) { metric =>
               printKey(metric.metricKey, maxNameLength) *>
                 printTags(metric.metricKey, maxTagSectionLength) *>
                 printValue(metric)
             }
           }
      _ <- printFooter("End of collected metrics")
    } yield ()

  private def printHeader(s: String): ZIO[Any, IOException, Unit] =
    Console.printLine("") *>
      Console.printLine(SC.YELLOW + s"## $s ##" + SC.RESET)

  private def printFooter(s: String): ZIO[Any, IOException, Unit] =
    Console.printLine(SC.YELLOW + s"## $s ##" + SC.RESET) *>
      Console.printLine("")

  private def printKey(key: MetricKey[_], padTo: Int): ZIO[Any, IOException, Unit] =
    Console.print(key.name.padTo(padTo, ' '))

  private def printTags(key: MetricKey[_], padTo: Int): ZIO[Any, IOException, Unit] = {
    val l = tagsToString(key.tags, useColors = false).length
    Console.print(tagsToString(key.tags, useColors = true) + " " * math.max(0, (padTo - l)))
  }

  private def printValue(metric: MetricPair[_, _]): ZIO[Any, IOException, Unit] =
    metric.metricState match {
      case MetricState.Counter(count)         => Console.printLine(count.toString)
      case MetricState.Frequency(occurrences) => Console.printLine(occurrences.toString())
      case MetricState.Gauge(value)           => Console.printLine(value.toString)
      case MetricState.Histogram(_, count, min, max, sum) =>
        Console.printLine(
          s"${SC.MAGENTA}min${SC.RESET}: $min, ${SC.MAGENTA}max${SC.RESET}: $max, ${SC.MAGENTA}avg${SC.RESET}: ${sum / count}"
        )
      case MetricState.Summary(_, _, count, min, max, sum) =>
        Console.printLine(
          s"${SC.MAGENTA}min${SC.RESET}: $min, ${SC.MAGENTA}max${SC.RESET}: $max, ${SC.MAGENTA}avg${SC.RESET}: ${sum / count}"
        )
    }

  private def tagsToString(tags: Set[MetricLabel], useColors: Boolean): String = {
    val byName = tags.toList.sortBy(_.key)

    if (useColors)
      SC.RESET + "[" + byName
        .map(l => s"${SC.MAGENTA}${l.key}${SC.RESET}: ${SC.GREEN}${l.value}${SC.RESET}")
        .mkString(", ") + "]"
    else
      "[" + byName.map(l => s"${l.key}: ${l.value}").mkString(", ") + "]"
  }
}
