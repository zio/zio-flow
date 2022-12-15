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

package zio.flow.runtime.internal

import zio.flow.runtime.IndexedStore.Index
import zio.flow.runtime.{ExecutionEnvironment, ExecutorError, metrics}
import zio.flow.runtime.metrics.{VariableAccess, VariableKind}
import zio.flow.runtime.{DurableLog, KeyValueStore, Timestamp}
import zio.flow.RemoteVariableName
import zio.stm.{TRef, TSet}
import zio.stream.ZStream
import zio.{Chunk, IO, UIO, ZIO, ZLayer}

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
  ): IO[ExecutorError, Boolean] = {
    ZIO.logTrace(s"Storing variable $name in scope $scope with timestamp ${timestamp.value}") *>
      metrics.variableSizeBytes(kindOf(scope)).update(value.size) *>
      keyValueStore
        .put(Namespaces.variables, key(ScopedRemoteVariableName(name, scope)), value, timestamp)
        .mapError(ExecutorError.KeyValueStoreError("put", _)) <*
      durableLog
        .append(
          Topics.variableChanges(scope.rootScope.flowId),
          executionEnvironment.codecs.encode(ScopedRemoteVariableName(name, scope))
        )
        .mapError(ExecutorError.LogError)
        .flatMap { index =>
          lastIndex.set(index).commit
        }
  } @@ metrics.variableAccessCount(VariableAccess.Write, kindOf(scope))

  def getLatest(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    before: Option[Timestamp]
  ): IO[ExecutorError, Option[(Chunk[Byte], RemoteVariableScope)]] = {
    keyValueStore
      .getLatest(Namespaces.variables, key(ScopedRemoteVariableName(name, scope)), before)
      .mapError(ExecutorError.KeyValueStoreError("getLatest", _))
      .flatMap {
        case Some(value) =>
          ZIO.logTrace(s"Read variable $name from scope $scope before $before") *>
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
  } @@ metrics.variableAccessCount(VariableAccess.Read, kindOf(scope))

  def getLatestTimestamp(
    name: RemoteVariableName,
    scope: RemoteVariableScope
  ): IO[ExecutorError, Option[(Timestamp, RemoteVariableScope)]] =
    keyValueStore
      .getLatestTimestamp(Namespaces.variables, key(ScopedRemoteVariableName(name, scope)))
      .mapError(ExecutorError.KeyValueStoreError("getLatestTimestamp", _))
      .flatMap {
        case Some(value) =>
          ZIO.logTrace(s"Found latest timestamp of $name in scope $scope: $value") *>
            ZIO.some((value, scope))
        case None =>
          scope.parentScope match {
            case Some(parentScope) =>
              getLatestTimestamp(name, parentScope)
            case None =>
              ZIO.none
          }
      }

  def getAllTimestamps(name: RemoteVariableName, scope: RemoteVariableScope): ZStream[Any, ExecutorError, Timestamp] =
    keyValueStore
      .getAllTimestamps(Namespaces.variables, key(ScopedRemoteVariableName(name, scope)))
      .mapError(ExecutorError.KeyValueStoreError("getAllTimestamps", _))

  def delete(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    marker: Option[Timestamp]
  ): IO[ExecutorError, Unit] = {
    ZIO.logTrace(
      s"Deleting ${RemoteVariableName.unwrap(name)} from scope $scope with${marker.map(ts => s" marker ${ts.value}").getOrElse(" no marker")}"
    ) *>
      keyValueStore
        .delete(Namespaces.variables, key(ScopedRemoteVariableName(name, scope)), marker)
        .mapError(ExecutorError.KeyValueStoreError("delete", _))
  } @@ metrics.variableAccessCount(VariableAccess.Delete, kindOf(scope))

  def getReadVariables: IO[Nothing, Set[ScopedRemoteVariableName]] =
    readVariables.toSet.commit

  def getLatestIndex: UIO[Index] =
    lastIndex.get.commit

  def allStoredVariables: ZStream[Any, ExecutorError, ScopedRemoteVariableName] =
    keyValueStore
      .scanAllKeys(Namespaces.variables)
      .mapBoth(
        ExecutorError.KeyValueStoreError("scanAllKeys", _),
        bytes =>
          Chunk.fromIterable(ScopedRemoteVariableName.fromString(new String(bytes.toArray, StandardCharsets.UTF_8)))
      )
      .flattenChunks

  private def key(name: ScopedRemoteVariableName): Chunk[Byte] =
    Chunk.fromArray(name.asString.getBytes(StandardCharsets.UTF_8))

  private def kindOf(scope: RemoteVariableScope): VariableKind =
    scope match {
      case RemoteVariableScope.TopLevel(_)         => VariableKind.TopLevel
      case RemoteVariableScope.Fiber(_, _)         => VariableKind.Forked
      case RemoteVariableScope.Transactional(_, _) => VariableKind.Transactional
    }
}

object RemoteVariableKeyValueStore {
  val layer: ZLayer[ExecutionEnvironment with DurableLog with KeyValueStore, Nothing, RemoteVariableKeyValueStore] =
    ZLayer {
      for {
        kvStore              <- ZIO.service[KeyValueStore]
        readVariables        <- TSet.empty[ScopedRemoteVariableName].commit
        durableLog           <- ZIO.service[DurableLog]
        lastIndex            <- TRef.make(Index(0L)).commit
        executionEnvironment <- ZIO.service[ExecutionEnvironment]
      } yield new RemoteVariableKeyValueStore(kvStore, readVariables, executionEnvironment, durableLog, lastIndex)
    }

  def put(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    value: Chunk[Byte],
    timestamp: Timestamp
  ): ZIO[RemoteVariableKeyValueStore, ExecutorError, Boolean] =
    ZIO.serviceWithZIO(_.put(name, scope, value, timestamp))

  def getLatest(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    before: Option[Timestamp]
  ): ZIO[RemoteVariableKeyValueStore, ExecutorError, Option[(Chunk[Byte], RemoteVariableScope)]] =
    ZIO.serviceWithZIO(_.getLatest(name, scope, before))

  def getLatestTimestamp(
    name: RemoteVariableName,
    scope: RemoteVariableScope
  ): ZIO[RemoteVariableKeyValueStore, ExecutorError, Option[(Timestamp, RemoteVariableScope)]] =
    ZIO.serviceWithZIO(_.getLatestTimestamp(name, scope))

  def getAllTimestamps(
    name: RemoteVariableName,
    scope: RemoteVariableScope
  ): ZStream[RemoteVariableKeyValueStore, ExecutorError, Timestamp] =
    ZStream.serviceWithStream(_.getAllTimestamps(name, scope))

  def delete(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    marker: Option[Timestamp]
  ): ZIO[RemoteVariableKeyValueStore, ExecutorError, Unit] =
    ZIO.serviceWithZIO(_.delete(name, scope, marker))

  def getReadVariables: ZIO[RemoteVariableKeyValueStore, Nothing, Set[ScopedRemoteVariableName]] =
    ZIO.serviceWithZIO(_.getReadVariables)

  def getLatestIndex: ZIO[RemoteVariableKeyValueStore, Nothing, Index] =
    ZIO.serviceWithZIO(_.getLatestIndex)

  def allStoredVariables: ZStream[RemoteVariableKeyValueStore, ExecutorError, ScopedRemoteVariableName] =
    ZStream.serviceWithStream(_.allStoredVariables)
}
