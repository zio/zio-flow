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

package zio.flow.runtime

import zio._
import zio.flow.runtime.internal.PersistentExecutor.FlowResult
import zio.flow.runtime.internal.{DefaultOperationExecutor, PersistentExecutor}
import zio.flow.runtime.operation.http.HttpOperationPolicies
import zio.flow.runtime.serialization.ExecutorBinaryCodecs
import zio.flow.{Configuration, FlowId, ZFlow}
import zio.schema.{DynamicValue, Schema}
import zio.stream.ZStream

trait ZFlowExecutor {

  /**
   * Submits a flow to be executed, and waits for it to complete.
   *
   * If the executor is already running a flow with the given ID, that flow's
   * result will be awaited.
   */
  def run[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): IO[E, A]

  /**
   * Submits a flow to be executed and returns a durable promise that will
   * complete when the flow completes.
   *
   * If the executor is already running a flow with the given ID, the existing
   * flow's durable promise will be returned
   */
  def start[E, A](
    id: FlowId,
    flow: ZFlow[Any, E, A]
  ): ZIO[Any, ExecutorError, DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]]

  /**
   * Poll currently running and complete workflows.
   *
   * If the workflow with the provided id is completed, it will be returned.
   */
  def poll(id: FlowId): ZIO[Any, ExecutorError, Option[Either[Either[ExecutorError, DynamicValue], DynamicValue]]]

  /**
   * Restart all known persisted running flows after recreating an executor.
   *
   * Executors with no support for persistence should do nothing.
   */
  def restartAll(): ZIO[Any, ExecutorError, Unit]

  /** Force a GC run manually */
  def forceGarbageCollection(): ZIO[Any, Nothing, Unit]

  /** Delete the persisted state and result of a completed workflow */
  def delete(id: FlowId): ZIO[Any, ExecutorError, Unit]

  /**
   * Pause a running flow. If the flow has been already finished or is already
   * paused, it has no effect.
   */
  def pause(id: FlowId): ZIO[Any, ExecutorError, Unit]

  /**
   * Resumes a paused flow. If the flow has been already finished or is already
   * running, it has no effect.
   */
  def resume(id: FlowId): ZIO[Any, ExecutorError, Unit]

  /**
   * Aborts a running flow. If the flow has been already finished, it has no
   * effect.
   */
  def abort(id: FlowId): ZIO[Any, ExecutorError, Unit]

  /**
   * Lists all the known flows, no matter if they are still running or already
   * finished
   */
  def getAll: ZStream[Any, ExecutorError, (FlowId, FlowStatus)]
}

object ZFlowExecutor {

  /**
   * Submits a flow to be executed, and waits for it to complete.
   *
   * If the executor is already running a flow with the given ID, that flow's
   * result will be awaited.
   */
  def run[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): ZIO[ZFlowExecutor, E, A] =
    ZIO.serviceWithZIO(_.run(id, flow))

  /**
   * Submits a flow to be executed and returns a durable promise that will
   * complete when the flow completes.
   *
   * If the executor is already running a flow with the given ID, the existing
   * flow's durable promise will be returned
   */
  def start[E, A](
    id: FlowId,
    flow: ZFlow[Any, E, A]
  ): ZIO[ZFlowExecutor, ExecutorError, DurablePromise[Either[ExecutorError, DynamicValue], FlowResult]] =
    ZIO.serviceWithZIO(_.start(id, flow))

  /**
   * Poll currently running and complete workflows.
   *
   * If the workflow with the provided id is completed, it will be returned.
   */
  def poll(
    id: FlowId
  ): ZIO[ZFlowExecutor, ExecutorError, Option[Either[Either[ExecutorError, DynamicValue], DynamicValue]]] =
    ZIO.serviceWithZIO(_.poll(id))

  /**
   * Restart all known persisted running flows after recreating an executor.
   *
   * Executors with no support for persistence should do nothing.
   */
  def restartAll(): ZIO[ZFlowExecutor, ExecutorError, Unit] =
    ZIO.serviceWithZIO(_.restartAll())

  /** Force a GC run manually */
  def forceGarbageCollection(): ZIO[ZFlowExecutor, Nothing, Unit] =
    ZIO.serviceWithZIO(_.forceGarbageCollection())

  val default: ZLayer[
    KeyValueStore with IndexedStore with ExecutorBinaryCodecs with Configuration,
    Nothing,
    ZFlowExecutor
  ] =
    ZLayer
      .makeSome[KeyValueStore with IndexedStore with ExecutorBinaryCodecs with Configuration, ZFlowExecutor](
        DurableLog.layer,
        DefaultOperationExecutor.layer,
        HttpOperationPolicies.disabled,
        PersistentExecutor.make()
      )

  val defaultJson: ZLayer[KeyValueStore with IndexedStore with Configuration, Nothing, ZFlowExecutor] =
    ZLayer.makeSome[KeyValueStore with IndexedStore with Configuration, ZFlowExecutor](
      ZLayer.succeed(serialization.json),
      default
    )

  val defaultProtobuf: ZLayer[KeyValueStore with IndexedStore with Configuration, Nothing, ZFlowExecutor] =
    ZLayer.makeSome[KeyValueStore with IndexedStore with Configuration, ZFlowExecutor](
      ZLayer.succeed(serialization.protobuf),
      default
    )

  val defaultInMemoryJson: ZLayer[Configuration, Nothing, ZFlowExecutor] =
    ZLayer.makeSome[Configuration, ZFlowExecutor](
      KeyValueStore.inMemory,
      IndexedStore.inMemory,
      ZLayer.succeed(serialization.json),
      default
    )

  val defaultInMemoryProtobuf: ZLayer[Configuration, Nothing, ZFlowExecutor] =
    ZLayer.makeSome[Configuration, ZFlowExecutor](
      KeyValueStore.inMemory,
      IndexedStore.inMemory,
      ZLayer.succeed(serialization.protobuf),
      default
    )
}
