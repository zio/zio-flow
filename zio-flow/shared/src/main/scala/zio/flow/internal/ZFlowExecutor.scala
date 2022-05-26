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

import zio._
import zio.flow.{FlowId, ZFlow}
import zio.schema.{DynamicValue, Schema}

import java.io.IOException

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
  ): ZIO[Any, IOException, DurablePromise[Either[Throwable, DynamicValue], DynamicValue]]

  /**
   * Poll currently running and complete workflows.
   *
   * If the workflow with the provided id is completed, it will be returned.
   */
  def pollWorkflowDynTyped(id: FlowId): ZIO[Any, Exception, Option[IO[DynamicValue, DynamicValue]]]

  /**
   * Restart all known persisted running flows after recreating an executor.
   *
   * Executors with no support for persistency should do nothing.
   */
  def restartAll(): ZIO[Any, IOException, Unit]
}

object ZFlowExecutor {}
