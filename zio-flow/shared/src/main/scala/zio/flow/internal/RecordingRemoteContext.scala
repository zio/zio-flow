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

package zio.flow.internal

import zio.flow.{RemoteContext, RemoteVariableName}
import zio.schema.DynamicValue
import zio.stm.TMap
import zio.{Chunk, UIO, ZIO}

trait RecordingRemoteContext {
  def getModifiedVariables: ZIO[Any, Nothing, Chunk[(RemoteVariableName, DynamicValue)]]
  def remoteContext: RemoteContext
  def commitContext: RemoteContext
  def virtualClock: VirtualClock
}

object RecordingRemoteContext {
  def startRecording: ZIO[RemoteContext with VirtualClock, Nothing, RecordingRemoteContext] =
    ZIO.service[RemoteContext].flatMap { outerRemoteContext =>
      ZIO.service[VirtualClock].flatMap { vclock =>
        TMap.empty[RemoteVariableName, DynamicValue].commit.map { cache =>
          new RecordingRemoteContext {
            override def commitContext: RemoteContext = outerRemoteContext

            override def getModifiedVariables: ZIO[Any, Nothing, Chunk[(RemoteVariableName, DynamicValue)]] =
              cache.toChunk.commit

            override val remoteContext: RemoteContext =
              new RemoteContext {
                override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
                  cache.put(name, value).commit

                override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
                  cache.get(name).commit.flatMap {
                    case Some(result) => ZIO.some(result)
                    case None         => outerRemoteContext.getVariable(name)
                  }

                override def getLatestTimestamp(name: RemoteVariableName): UIO[Option[Timestamp]] =
                  outerRemoteContext.getLatestTimestamp(name)

                override def dropVariable(name: RemoteVariableName): UIO[Unit] =
                  cache.delete(name).commit
              }

            override def virtualClock: VirtualClock = vclock
          }
        }
      }
    }
}
