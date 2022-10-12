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
import zio.flow.serialization.{Deserializer, Serializer}
import zio.flow.{Configuration, FlowId, ZFlow}
import zio.schema.{DynamicValue, Schema}

trait ZFlowExecutor {

  /**
   * Submits a flow to be executed, and waits for it to complete.
   *
   * If the executor is already running a flow with the given ID, that flow's
   * result will be awaited.
   */
  def submit[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): IO[E, A]

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
  def pollWorkflowDynTyped(id: FlowId): ZIO[Any, ExecutorError, Option[IO[DynamicValue, FlowResult]]]

  /**
   * Restart all known persisted running flows after recreating an executor.
   *
   * Executors with no support for persistence should do nothing.
   */
  def restartAll(): ZIO[Any, ExecutorError, Unit]

  /** Force a GC run manually */
  def forceGarbageCollection(): ZIO[Any, Nothing, Unit]
}

object ZFlowExecutor {

  /**
   * Submits a flow to be executed, and waits for it to complete.
   *
   * If the executor is already running a flow with the given ID, that flow's
   * result will be awaited.
   */
  def submit[E: Schema, A: Schema](id: FlowId, flow: ZFlow[Any, E, A]): ZIO[ZFlowExecutor, E, A] =
    ZIO.serviceWithZIO(_.submit(id, flow))

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
  def pollWorkflowDynTyped(id: FlowId): ZIO[ZFlowExecutor, ExecutorError, Option[IO[DynamicValue, FlowResult]]] =
    ZIO.serviceWithZIO(_.pollWorkflowDynTyped(id))

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
    KeyValueStore with IndexedStore with Serializer with Deserializer with Configuration,
    Nothing,
    ZFlowExecutor
  ] =
    ZLayer
      .makeSome[KeyValueStore with IndexedStore with Serializer with Deserializer with Configuration, ZFlowExecutor](
        DurableLog.layer,
        DefaultOperationExecutor.layer,
        PersistentExecutor.make()
      )

  val defaultJson: ZLayer[KeyValueStore with IndexedStore with Configuration, Nothing, ZFlowExecutor] =
    ZLayer.makeSome[KeyValueStore with IndexedStore with Configuration, ZFlowExecutor](
      ZLayer.succeed(Serializer.json),
      ZLayer.succeed(Deserializer.json),
      default
    )

  val defaultProtobuf: ZLayer[KeyValueStore with IndexedStore with Configuration, Nothing, ZFlowExecutor] =
    ZLayer.makeSome[KeyValueStore with IndexedStore with Configuration, ZFlowExecutor](
      ZLayer.succeed(Serializer.protobuf),
      ZLayer.succeed(Deserializer.protobuf),
      default
    )

  val defaultInMemoryJson: ZLayer[Configuration, Nothing, ZFlowExecutor] =
    ZLayer.makeSome[Configuration, ZFlowExecutor](
      KeyValueStore.inMemory,
      IndexedStore.inMemory,
      ZLayer.succeed(Serializer.json),
      ZLayer.succeed(Deserializer.json),
      default
    )

  val defaultInMemoryProtobuf: ZLayer[Configuration, Nothing, ZFlowExecutor] =
    ZLayer.makeSome[Configuration, ZFlowExecutor](
      KeyValueStore.inMemory,
      IndexedStore.inMemory,
      ZLayer.succeed(Serializer.protobuf),
      ZLayer.succeed(Deserializer.protobuf),
      default
    )
}
