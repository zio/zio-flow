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

import zhttp.service.{ChannelFactory, EventLoopGroup}
import zio.flow.Operation.{ContraMap, Http, Map}
import zio.flow.runtime.operation.http.{HttpOperationPolicies, HttpOperationPolicy}
import zio.flow.{ActivityError, Operation, OperationExecutor, Remote, RemoteContext}
import zio.schema.Schema
import zio.stm.{TMap, TPromise}
import zio.{ZEnvironment, ZIO, ZLayer}

final case class DefaultOperationExecutor(
  env: ZEnvironment[EventLoopGroup with ChannelFactory],
  policies: HttpOperationPolicies,
  perHostRetryLogic: TMap[String, TPromise[Nothing, HttpRetryLogic]]
) extends OperationExecutor {

  override def execute[Input, Result](
    input: Input,
    operation: Operation[Input, Result]
  ): ZIO[RemoteContext, ActivityError, Result] =
    operation match {
      case ContraMap(inner, f, schema) =>
        RemoteContext
          .eval(f(Remote(input)(schema.asInstanceOf[Schema[Input]])))(inner.inputSchema)
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
        val policy = policies.policyForHost(host)
        getOrCreateRetryLogic(host, policy).flatMap { retryLogic =>
          val finalHost = policy.hostOverride.getOrElse(host)
          ZIO.logInfo(s"Request $operation") *>
            retryLogic(api.method, finalHost) {
              api
                .call(finalHost)(input)
                .sandbox
                .tapBoth(
                  failure =>
                    ZIO.logErrorCause(s"Request ${api.method} $finalHost failed", failure) *>
                      ZIO.fail(failure),
                  result => ZIO.logDebug(s"Request ${api.method} $finalHost succeeded with result $result")
                )
            }.provideEnvironment(env)
        }
      case _ =>
        ZIO.dieMessage(s"Unsupported operation ${operation.getClass.getName}")
    }

  private def getOrCreateRetryLogic(host: String, policy: HttpOperationPolicy): ZIO[Any, Nothing, HttpRetryLogic] =
    perHostRetryLogic
      .get(host)
      .flatMap {
        case Some(promise) => promise.await.map(Right(_))
        case None =>
          TPromise.make[Nothing, HttpRetryLogic].flatMap { promise =>
            perHostRetryLogic.put(host, promise).as(Left(promise))
          }
      }
      .commit
      .flatMap {
        case Left(promise) => HttpRetryLogic.make(policy).flatMap(logic => promise.succeed(logic).commit.as(logic))
        case Right(logic)  => ZIO.succeed(logic)
      }
}

object DefaultOperationExecutor {
  val layer: ZLayer[HttpOperationPolicies, Nothing, OperationExecutor] =
    ZLayer.scoped {
      for {
        env         <- (EventLoopGroup.auto(0) ++ ChannelFactory.auto).build
        policies    <- ZIO.service[HttpOperationPolicies]
        retryLogics <- TMap.empty[String, TPromise[Nothing, HttpRetryLogic]].commit
      } yield DefaultOperationExecutor(env, policies, retryLogics)
    }
}
