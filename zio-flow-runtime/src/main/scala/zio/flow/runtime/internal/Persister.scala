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

import zio.{Clock, Duration, NonEmptyChunk, Ref, ZIO}
import zio.flow.FlowId
import zio.flow.runtime.internal.PersistentExecutor.{State, StateChange}
import zio.flow.runtime.serialization.ExecutorBinaryCodecs
import zio.flow.runtime._

import java.time.OffsetDateTime

trait Persister {
  def saveStateChange[E, A](
    baseState: State[E, A],
    changes: StateChange,
    currentTimestamp: Timestamp
  ): ZIO[Any, ExecutorError, State[E, A]]
}

object Persister {
  final case class SnapshotOnly(id: FlowId, kvStore: KeyValueStore, codecs: ExecutorBinaryCodecs) extends Persister {
    override def saveStateChange[E, A](
      baseState: State[E, A],
      changes: StateChange,
      currentTimestamp: Timestamp
    ): ZIO[Any, ExecutorError, State[E, A]] =
      for {
        updatedState  <- ZIO.succeed(changes(baseState))
        persistedState = codecs.encode(updatedState.asInstanceOf[State[Any, Any]])
        key            = id.toRaw
        _             <- metrics.serializedFlowStateSize.update(persistedState.size)
        _ <- kvStore
               .put(Namespaces.workflowState, key, persistedState, currentTimestamp)
               .mapError(ExecutorError.KeyValueStoreError("put", _))
        _ <- ZIO.logTrace(s"State of $id persisted")
      } yield updatedState
  }

  final case class JournalAndSnapshot(
    id: FlowId,
    kvStore: KeyValueStore,
    indexedStore: IndexedStore,
    codecs: ExecutorBinaryCodecs,
    state: Ref[JournalAndSnapshot.State],
    afterEvery: Option[Int],
    afterDuration: Option[Duration]
  ) extends Persister {
    private val topicName = Topics.journal(id)

    override def saveStateChange[E, A](
      baseState: State[E, A],
      changes: StateChange,
      currentTimestamp: Timestamp
    ): ZIO[Any, ExecutorError, State[E, A]] =
      NonEmptyChunk.fromChunk(changes.toChunk) match {
        case Some(changesChunk) =>
          for {
            persisterState <- state.get
            indices <- ZIO
                         .foreach(changesChunk) { entry =>
                           val bytes = codecs.encode(entry)
                           metrics.serializedStateChangeSize(entry).update(bytes.size) *>
                             indexedStore
                               .put(topicName, bytes)
                               .mapError(ExecutorError.IndexedStoreError("put", _))
                         }
            newState <- ZIO.succeed(changes(baseState))
            newCount  = persisterState.changeCount + changesChunk.size
            now      <- Clock.currentDateTime
            doSnapshot = persisterState.initial ||
                           afterEvery.exists(n => newCount > n) ||
                           afterDuration.exists(d => persisterState.lastSnapshot.plus(d).isBefore(now))
            newPersisterState <-
              if (doSnapshot) {
                val lastIndex                = indices.last
                val newStateWithJournalIndex = newState.copy(journalIndex = Some(lastIndex))
                val persistedState           = codecs.encode(newStateWithJournalIndex.asInstanceOf[State[Any, Any]])
                val key                      = id.toRaw
                for {
                  _ <- metrics.serializedFlowStateSize.update(persistedState.size)
                  _ <- kvStore
                         .put(Namespaces.workflowState, key, persistedState, currentTimestamp)
                         .mapError(ExecutorError.KeyValueStoreError("put", _))
                  _ <- ZIO.logTrace(s"Snapshot state of $id persisted with last journal index $lastIndex")
                } yield persisterState.copy(initial = false, changeCount = 0, lastSnapshot = now)
              } else {
                ZIO.logTrace(s"Saved ${changesChunk.size} state changes, no snapshot") *>
                  ZIO.succeed(persisterState.copy(initial = false, changeCount = newCount))
              }
            _ <- state.set(newPersisterState)
          } yield newState
        case None =>
          ZIO.succeed(baseState)
      }
  }

  object JournalAndSnapshot {
    final case class State(initial: Boolean, changeCount: Int, lastSnapshot: OffsetDateTime)
  }
}
