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

import zio.ZNothing
import zio.flow._

final class RemoteInstantCompanionSyntax(val instant: Instant.type) extends AnyVal {
  def now: ZFlow[Any, ZNothing, Instant] =
    ZFlow.now

  def ofEpochSecond(second: Remote[Long]): Remote[Instant] =
    ofEpochSecond(second, 0L)

  def ofEpochSecond(second: Remote[Long], nanos: Remote[Long]): Remote[Instant] =
    Remote.Unary(Remote.tuple2((second, nanos.toInt)), UnaryOperators.Conversion(RemoteConversions.TupleToInstant))

  def ofEpochMilli(milliSecond: Remote[Long]): Remote[Instant] =
    ofEpochSecond(
      milliSecond / 1000L,
      (milliSecond % 1000L) * 1000000L
    )

  def parse(charSeq: Remote[String]): Remote[Instant] =
    Remote.Unary(charSeq, UnaryOperators.Conversion(RemoteConversions.StringToInstant))
}
