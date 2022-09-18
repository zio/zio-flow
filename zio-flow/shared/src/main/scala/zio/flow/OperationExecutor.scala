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

package zio.flow

import zhttp.service.{ChannelFactory, EventLoopGroup}
import zio.flow.Operation.Http
import zio.flow.operation.http._
import zio.{ZEnvironment, ZIO, ZLayer}

/**
 * An `OperationExecutor` can execute operations, or fail trying.
 *
 * TODO: Delete R from operation executor
 */
trait OperationExecutor[-R] { self =>
  def execute[I, A](input: I, operation: Operation[I, A]): ZIO[R, ActivityError, A]

  def provideEnvironment(env: ZEnvironment[R]): OperationExecutor[Any] =
    new OperationExecutor[Any] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Any, ActivityError, A] =
        self.execute(input, operation).provideEnvironment(env)
    }
}

object OperationExecutor {
  val live: ZLayer[Any, Nothing, OperationExecutor[Any]] = ZLayer.scoped {
    for {
      operationExecutorEnvironment <- (EventLoopGroup.auto(0) ++ ChannelFactory.auto).build
    } yield OperationExecutorImpl().provideEnvironment(operationExecutorEnvironment)
  }
}

case class OperationExecutorImpl() extends OperationExecutor[EventLoopGroup with ChannelFactory] {
  override def execute[I, A](
    input: I,
    operation: Operation[I, A]
  ): ZIO[EventLoopGroup with ChannelFactory, ActivityError, A] =
    operation match {
      case Http(host, api) =>
        api.call(host)(input).mapError(e => ActivityError(s"Failed ${api.method} request to $host", Option(e)))
    }
}
