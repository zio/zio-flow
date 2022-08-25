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

import zio.flow.internal.IndexedStore.Index
import zio.flow.{ExecutionEnvironment, RemoteVariableName}
import zio.stm.{TRef, TSet}
import zio.stream.ZStream
import zio.{Chunk, IO, UIO, ZIO, ZLayer}

import java.io.IOException
import java.nio.charset.StandardCharsets

final case class RemoteVariableKeyValueStore(
  keyValueStore: KeyValueStore,
  readVariables: TSet[ScopedRemoteVariableName],
  executionEnvironment: ExecutionEnvironment,
  durableLog: DurableLog,
  lastIndex: TRef[Index]
) {
  def put(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    value: Chunk[Byte],
    timestamp: Timestamp
  ): IO[IOException, Boolean] =
    ZIO.logDebug(s"Storing variable ${RemoteVariableName.unwrap(name)} in scope $scope with timestamp $timestamp") *>
      keyValueStore.put(Namespaces.variables, key(ScopedRemoteVariableName(name, scope)), value, timestamp) <*
      durableLog
        .append(
          Topics.variableChanges(scope.rootScope.flowId),
          executionEnvironment.serializer.serialize(ScopedRemoteVariableName(name, scope))
        )
        .orDie // TODO: rethink/cleanup error handling
        .flatMap { index =>
          lastIndex.set(index).commit
        }

  def getLatest(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    before: Option[Timestamp]
  ): IO[IOException, Option[(Chunk[Byte], RemoteVariableScope)]] =
    keyValueStore.getLatest(Namespaces.variables, key(ScopedRemoteVariableName(name, scope)), before).flatMap {
      case Some(value) =>
        ZIO.logDebug(s"Read variable ${RemoteVariableName.unwrap(name)} from scope $scope before $before") *>
          readVariables
            .put(ScopedRemoteVariableName(name, scope))
            .as(Some((value, scope)))
            .commit
      case None =>
        scope.parentScope match {
          case Some(parentScope) =>
            getLatest(name, parentScope, before)
          case None =>
            ZIO.none
        }
    }

  def getLatestTimestamp(
    name: RemoteVariableName,
    scope: RemoteVariableScope
  ): IO[IOException, Option[(Timestamp, RemoteVariableScope)]] =
    keyValueStore.getLatestTimestamp(Namespaces.variables, key(ScopedRemoteVariableName(name, scope))).flatMap {
      case Some(value) =>
//        ZIO.logDebug(s"Read latest timestamp of variable ${RemoteVariableName.unwrap(name)} from scope $scope") *>
        ZIO.some((value, scope))
      case None =>
        scope.parentScope match {
          case Some(parentScope) =>
            getLatestTimestamp(name, parentScope)
          case None =>
            ZIO.none
        }
    }

  def delete(name: RemoteVariableName, scope: RemoteVariableScope): IO[IOException, Unit] =
    ZIO.logDebug(s"Deleting ${RemoteVariableName.unwrap(name)} from scope $scope") *>
      keyValueStore.delete(Namespaces.variables, key(ScopedRemoteVariableName(name, scope)))

  def getReadVariables: IO[Nothing, Set[ScopedRemoteVariableName]] =
    readVariables.toSet.commit

  def getLatestIndex: UIO[Index] =
    lastIndex.get.commit

  def allStoredVariables: ZStream[Any, IOException, ScopedRemoteVariableName] =
    keyValueStore
      .scanAllKeys(Namespaces.variables)
      .map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
      .map(str => Chunk.fromIterable(ScopedRemoteVariableName.fromString(str)))
      .flattenChunks

  private def key(name: ScopedRemoteVariableName): Chunk[Byte] =
    Chunk.fromArray(name.asString.getBytes(StandardCharsets.UTF_8))
}

object RemoteVariableKeyValueStore {
  val live: ZLayer[ExecutionEnvironment with DurableLog with KeyValueStore, Nothing, RemoteVariableKeyValueStore] =
    ZLayer {
      for {
        kvStore              <- ZIO.service[KeyValueStore]
        readVariables        <- TSet.empty[ScopedRemoteVariableName].commit
        durableLog           <- ZIO.service[DurableLog]
        lastIndex            <- TRef.make(Index(0L)).commit
        executionEnvironment <- ZIO.service[ExecutionEnvironment]
      } yield new RemoteVariableKeyValueStore(kvStore, readVariables, executionEnvironment, durableLog, lastIndex)
    }

  def getReadVariables: ZIO[RemoteVariableKeyValueStore, Nothing, Set[ScopedRemoteVariableName]] =
    ZIO.serviceWithZIO(_.getReadVariables)

  def getLatestIndex: ZIO[RemoteVariableKeyValueStore, Nothing, Index] =
    ZIO.serviceWithZIO(_.getLatestIndex)

  def allStoredVariables: ZStream[RemoteVariableKeyValueStore, IOException, ScopedRemoteVariableName] =
    ZStream.serviceWithStream(_.allStoredVariables)
}
