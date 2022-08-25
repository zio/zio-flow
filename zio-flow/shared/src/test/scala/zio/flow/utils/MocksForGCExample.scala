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

package zio.flow.utils

import zio._
import zio.flow.{ActivityError, Operation, OperationExecutor}

import java.net.URI

object MocksForGCExample {

  def mockOpExec2(map: Map[URI, Any]): OperationExecutor[Console with Clock] =
    new OperationExecutor[Console with Clock] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Console with Clock, ActivityError, A] =
        operation match {
          case Operation.Http(url, _) =>
            Console
              .printLine(s"Request to : $url")
              .mapBoth(
                error => ActivityError("Failed to write to console", Some(error)),
                _ => map(new URI(url)).asInstanceOf[A]
              )
        }
    }

//  val mockInMemoryForGCExample: ZIO[Any, Nothing, InMemory[String, Clock with Console]] = Ref
//    .make[Map[String, Ref[InMemory.State]]](Map.empty)
//    .map(ref =>
//      InMemory(
//        ZEnvironment(Clock.ClockLive) ++ ZEnvironment(Console.ConsoleLive),
//        ExecutionEnvironment(Serializer.protobuf, Deserializer.protobuf),
//        mockOpExec2(mockResponseMap),
//        ref
//      )
//    )
//
//  private val mockResponseMap: Map[URI, Any] = Map(
//    new URI("getPolicyClaimStatus.com")   -> true,
//    new URI("getFireRiskForProperty.com") -> 0.23,
//    new URI("isManualEvalRequired.com")   -> true,
//    new URI("createRenewedPolicy.com")    -> Some(Policy("DummyPolicy"))
//  )
}
