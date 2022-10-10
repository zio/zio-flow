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

import zhttp.service.{ChannelFactory, EventLoopGroup}
import zio.{ZEnvironment, ZIO, ZLayer}
import zio.flow.Operation.{ContraMap, Http, Map}
import zio.flow.{ActivityError, Operation, OperationExecutor, Remote, RemoteContext}
import zio.schema.Schema

final case class DefaultOperationExecutor(env: ZEnvironment[EventLoopGroup with ChannelFactory])
    extends OperationExecutor {

  override def execute[Input, Result](
    input: Input,
    operation: Operation[Input, Result]
  ): ZIO[RemoteContext, ActivityError, Result] =
    operation match {
      case ContraMap(inner, f, schema) =>
        RemoteContext
          .eval(f(Remote(input)(operation.inputSchema.asInstanceOf[Schema[Input]])))(schema)
          .mapError(executionError => ActivityError("Failed to transform input", Some(executionError.toException)))
          .flatMap { input2 =>
            execute(input2, inner)
          }
      case Map(inner, f, schema) =>
        execute(input, inner).flatMap { result =>
          RemoteContext
            .eval(f(Remote(result)(inner.resultSchema.asInstanceOf[Schema[Any]])))(schema)
            .mapError(executionError => ActivityError("Failed to transform output", Some(executionError.toException)))
        }
      case Http(host, api) =>
        api
          .call(host)(input)
          .mapError(e => ActivityError(s"Failed ${api.method} request to $host", Option(e)))
          .provideEnvironment(env)
    }
}

object DefaultOperationExecutor {
  val live: ZLayer[Any, Nothing, OperationExecutor] =
    ZLayer.scoped {
      for {
        env <- (EventLoopGroup.auto(0) ++ ChannelFactory.auto).build
      } yield DefaultOperationExecutor(env)
    }
}
