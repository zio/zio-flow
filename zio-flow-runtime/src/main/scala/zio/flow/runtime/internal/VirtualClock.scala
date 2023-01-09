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

package zio.flow.runtime.internal

import zio.flow.runtime.Timestamp
import zio.{Ref, UIO, ZIO}

trait VirtualClock {
  def current: UIO[Timestamp]
  def advance(toLargerThan: Timestamp): UIO[Unit]
}

object VirtualClock {
  def make(initial: Timestamp): ZIO[Any, Nothing, VirtualClock] =
    Ref.make(initial).map { timestamp =>
      new VirtualClock {
        def current: UIO[Timestamp]                     = timestamp.get
        def advance(toLargerThan: Timestamp): UIO[Unit] = timestamp.update(current => current.max(toLargerThan).next)
      }
    }

  def current: ZIO[VirtualClock, Nothing, Timestamp] =
    ZIO.serviceWithZIO(_.current)

  def advance(toLargerThan: Timestamp): ZIO[VirtualClock, Nothing, Unit] =
    ZIO.serviceWithZIO(_.advance(toLargerThan))
}
