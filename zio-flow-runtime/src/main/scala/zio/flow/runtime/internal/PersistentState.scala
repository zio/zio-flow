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

import zio.{Duration, IO, Ref, ZIO, ZLayer}
import zio.flow.FlowId
import zio.flow.runtime.IndexedStore.Index
import zio.flow.runtime.internal.PersistentExecutor.{FlowResult, StateChange}
import zio.flow.runtime.serialization.ExecutorBinaryCodecs
import zio.flow.runtime.{DurablePromise, ExecutorError, IndexedStore, KeyValueStore, PersisterConfig}
import zio.schema.DynamicValue

import java.time.OffsetDateTime

trait PersistentState {

  /** Loads a persisted executor state */
  def load(id: FlowId): IO[ExecutorError, Option[PersistentExecutor.State[_, _]]]

  /**
   * Loads a persisted executor state, reusing an already read latest snapshot.
   *
   * This is an optimization to avoid reading the snapshot table twice in case
   * it was already read by enumerating all running states.
   */
  def loadUsingSnapshot(
    id: FlowId,
    snapshot: PersistentExecutor.State[_, _]
  ): IO[ExecutorError, PersistentExecutor.State[_, _]]

  /** Creates a persister used to save state changes */
  def createPersister(id: FlowId): ZIO[Any, Nothing, Persister]

  /** Deletes all persisted state for a flow */
  def delete(
    id: FlowId
  ): ZIO[Any, ExecutorError, Option[DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]]]
}

object PersistentState {
  final case class SnapshotOnly(kvStore: KeyValueStore, codecs: ExecutorBinaryCodecs) extends PersistentState {
    override def load(id: FlowId): IO[ExecutorError, Option[PersistentExecutor.State[_, _]]] =
      for {
        _  <- ZIO.logInfo(s"Looking for persisted flow state $id")
        key = id.toRaw
        persistedState <- kvStore
                            .getLatest(Namespaces.workflowState, key, None)
                            .mapError(ExecutorError.KeyValueStoreError("getLatest", _))
        state <- persistedState match {
                   case Some(bytes) =>
                     ZIO.logInfo(s"Using persisted state for $id (${bytes.size} bytes)") *>
                       ZIO
                         .fromEither(codecs.decode[PersistentExecutor.State[Any, Any]](bytes))
                         .mapBoth(error => ExecutorError.DeserializationError(s"state of $id", error.message), Some(_))
                   case None => ZIO.logInfo("No persisted state available").as(None)
                 }
      } yield state

    override def loadUsingSnapshot(
      id: FlowId,
      snapshot: PersistentExecutor.State[_, _]
    ): IO[ExecutorError, PersistentExecutor.State[_, _]] =
      ZIO.succeed(snapshot)

    override def createPersister(id: FlowId): ZIO[Any, Nothing, Persister] =
      ZIO.succeed(Persister.SnapshotOnly(id, kvStore, codecs))

    override def delete(
      id: FlowId
    ): ZIO[Any, ExecutorError, Option[DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]]] =
      for {
        _     <- ZIO.logInfo(s"Deleting persisted state $id")
        state <- load(id)
        resultPromise <- state match {
                           case Some(state) =>
                             for {
                               _ <- kvStore
                                      .delete(Namespaces.workflowState, id.toRaw, None)
                                      .mapError(ExecutorError.KeyValueStoreError("delete", _))
                             } yield Some(state.result)
                           case None => ZIO.none
                         }
      } yield resultPromise
  }

  final case class JournalAndSnapshot(
    kvStore: KeyValueStore,
    indexedStore: IndexedStore,
    codecs: ExecutorBinaryCodecs,
    afterEvery: Option[Int],
    afterDuration: Option[Duration]
  ) extends PersistentState {
    override def load(id: FlowId): IO[ExecutorError, Option[PersistentExecutor.State[_, _]]] =
      for {
        _  <- ZIO.logInfo(s"Looking for persisted flow state snapshot $id")
        key = id.toRaw
        persistedState <- kvStore
                            .getLatest(Namespaces.workflowState, key, None)
                            .mapError(ExecutorError.KeyValueStoreError("getLatest", _))
        state <- persistedState match {
                   case Some(bytes) =>
                     for {
                       _ <- ZIO.logInfo(s"Using persisted state snapshot for $id (${bytes.size} bytes)")
                       snapshotState <-
                         ZIO
                           .fromEither(codecs.decode[PersistentExecutor.State[Any, Any]](bytes))
                           .mapError(error => ExecutorError.DeserializationError(s"state of $id", error.message))
                       finalState <- loadUsingSnapshot(id, snapshotState)
                     } yield Some(finalState)
                   case None => ZIO.logInfo("No persisted state available").as(None)
                 }
      } yield state

    override def loadUsingSnapshot(
      id: FlowId,
      snapshot: PersistentExecutor.State[_, _]
    ): IO[ExecutorError, PersistentExecutor.State[_, _]] = {
      val startIndex = snapshot.journalIndex.getOrElse(Index.initial)
      for {
        _ <- ZIO.logInfo(s"Looking for journal entries starting from $startIndex")
        finalState <-
          indexedStore
            .scan(Topics.journal(id), startIndex, Index.max)
            .mapError(ExecutorError.IndexedStoreError("scan", _))
            .mapZIO { bytes =>
              ZIO
                .fromEither(codecs.decode[StateChange](bytes))
                .mapError(decodeError => ExecutorError.DeserializationError(s"journal entry", decodeError.message))
            }
            .runFold(snapshot) { (state, change) =>
              change(state)
            }
      } yield finalState
    }

    override def createPersister(id: FlowId): ZIO[Any, Nothing, Persister] =
      for {
        state <- Ref.make(Persister.JournalAndSnapshot.State(initial = true, 0, OffsetDateTime.MIN))
      } yield Persister.JournalAndSnapshot(id, kvStore, indexedStore, codecs, state, afterEvery, afterDuration)

    /** Deletes all persisted state for a flow */
    override def delete(
      id: FlowId
    ): ZIO[Any, ExecutorError, Option[DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]]] =
      for {
        _     <- ZIO.logInfo(s"Deleting persisted state $id")
        state <- load(id)
        resultPromise <- state match {
                           case Some(state) =>
                             for {
                               _ <- kvStore
                                      .delete(Namespaces.workflowState, id.toRaw, None)
                                      .mapError(ExecutorError.KeyValueStoreError("delete", _))
                               _ <- indexedStore
                                      .delete(Topics.journal(id))
                                      .mapError(ExecutorError.IndexedStoreError("delete", _))
                             } yield Some(state.result)
                           case None => ZIO.none
                         }
      } yield resultPromise
  }

  def make(
    keyValueStore: KeyValueStore,
    indexedStore: IndexedStore,
    codecs: ExecutorBinaryCodecs,
    config: PersisterConfig
  ): ZIO[Any, Nothing, PersistentState] =
    config match {
      case PersisterConfig.SnapshotsOnly =>
        ZIO.succeed(SnapshotOnly(keyValueStore, codecs))
      case PersisterConfig.PeriodicSnapshots(afterEvery, afterDuration) =>
        ZIO.succeed(JournalAndSnapshot(keyValueStore, indexedStore, codecs, afterEvery, afterDuration))
    }

  val any: ZLayer[PersistentState, Nothing, PersistentState] = ZLayer.service[PersistentState]

  val snapshotOnly: ZLayer[ExecutorBinaryCodecs with IndexedStore with KeyValueStore, Nothing, PersistentState] =
    configured(PersisterConfig.SnapshotsOnly)

  def journalAndSnapshot(
    afterEvery: Option[Int],
    afterDuration: Option[Duration]
  ): ZLayer[ExecutorBinaryCodecs with IndexedStore with KeyValueStore, Nothing, PersistentState] =
    configured(PersisterConfig.PeriodicSnapshots(afterEvery, afterDuration))

  def configured(
    persisterConfig: PersisterConfig
  ): ZLayer[ExecutorBinaryCodecs with IndexedStore with KeyValueStore, Nothing, PersistentState] =
    ZLayer {
      for {
        keyValueStore <- ZIO.service[KeyValueStore]
        indexedStore  <- ZIO.service[IndexedStore]
        codecs        <- ZIO.service[ExecutorBinaryCodecs]
        persistentState <-
          make(keyValueStore, indexedStore, codecs, persisterConfig)
      } yield persistentState
    }
}
