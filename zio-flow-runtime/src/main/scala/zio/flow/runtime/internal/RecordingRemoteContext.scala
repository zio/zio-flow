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

import zio.flow.runtime.{DurableLog, ExecutionEnvironment, ExecutorError, Timestamp}
import zio.flow.{ConfigKey, RemoteContext, RemoteVariableName}
import zio.schema.{DynamicValue, Schema}
import zio.stm.{TMap, TPromise, ZSTM}
import zio.{Chunk, ZIO}

trait RecordingRemoteContext {
  def getModifiedVariables
    : ZIO[Any, Nothing, Map[RemoteContext, (RemoteVariableScope, Chunk[(RemoteVariableName, DynamicValue)])]]
  def remoteContext(scope: RemoteVariableScope): ZIO[Any, Nothing, RemoteContext]
  def virtualClock: VirtualClock
}

object RecordingRemoteContext {

  private class SingleRecordingRemoteContext(
    val scope: RemoteVariableScope,
    val outerRemoteContext: RemoteContext,
    val cache: TMap[RemoteVariableName, DynamicValue]
  ) {
    val remoteContext: RemoteContext = new RemoteContext {
      override def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[Any, ExecutorError, Unit] =
        cache.put(name, value).commit

      override def getVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[DynamicValue]] =
        cache.get(name).commit.flatMap {
          case Some(result) => ZIO.some(result)
          case None         => outerRemoteContext.getVariable(name)
        }

      override def getLatestTimestamp(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[Timestamp]] =
        outerRemoteContext.getLatestTimestamp(name)

      override def dropVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Unit] =
        cache.delete(name).commit

      override def readConfig[A: Schema](key: ConfigKey): ZIO[Any, ExecutorError, Option[A]] =
        outerRemoteContext.readConfig[A](key)
    }
  }

  def startRecording: ZIO[
    RemoteVariableKeyValueStore with ExecutionEnvironment with VirtualClock with DurableLog,
    Nothing,
    RecordingRemoteContext
  ] =
    ZIO.service[VirtualClock].flatMap { vclock =>
      ZIO.environment[RemoteVariableKeyValueStore with ExecutionEnvironment with VirtualClock with DurableLog].flatMap {
        env =>
          TMap.empty[RemoteVariableScope, TPromise[Nothing, SingleRecordingRemoteContext]].commit.map { contexts =>
            new RecordingRemoteContext {
              override def getModifiedVariables: ZIO[
                Any,
                Nothing,
                Map[RemoteContext, (RemoteVariableScope, Chunk[(RemoteVariableName, DynamicValue)])]
              ] =
                contexts.toMap.flatMap { contextMap =>
                  ZSTM.foreach(contextMap.values) { promise =>
                    promise.await.flatMap { singleRecordingContext =>
                      singleRecordingContext.cache.toChunk.map { kvs =>
                        (singleRecordingContext.outerRemoteContext, (singleRecordingContext.scope, kvs))
                      }
                    }
                  }
                }.commit.map(_.toMap)

              override def remoteContext(scope: RemoteVariableScope): ZIO[Any, Nothing, RemoteContext] =
                contexts
                  .get(scope)
                  .flatMap {
                    case Some(contextPromise) => contextPromise.await.map(ctx => ZIO.succeed(ctx))
                    case None =>
                      TPromise.make[Nothing, SingleRecordingRemoteContext].flatMap { promise =>
                        TMap.empty[RemoteVariableName, DynamicValue].flatMap { cache =>
                          contexts.put(scope, promise).as {
                            PersistentRemoteContext
                              .make(scope)
                              .provideEnvironment(env)
                              .map(new SingleRecordingRemoteContext(scope, _, cache))
                              .tap(ctx => promise.succeed(ctx).commit)
                          }
                        }
                      }
                  }
                  .commit
                  .flatten
                  .map(_.remoteContext)

              override def virtualClock: VirtualClock = vclock
            }
          }
      }
    }
}
