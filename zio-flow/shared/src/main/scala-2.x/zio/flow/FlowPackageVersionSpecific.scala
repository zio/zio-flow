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

import zio.prelude.Assertion._
import zio.prelude.Newtype
import zio.schema._
import zio.{Random, ZIO}

trait FlowPackageVersionSpecific {
  type RemoteVariableName = RemoteVariableName.Type

  object RemoteVariableName extends Newtype[String] {
    implicit val schema: Schema[RemoteVariableName] = Schema[String].transform(wrap(_), unwrap)

    override def assertion = assert {
      !contains("__")
    }

    def unsafeMake(name: String): RemoteVariableName = wrap(name)
  }

  type FlowId = FlowId.Type

  object FlowId extends Newtype[String] {
    implicit val schema: Schema[FlowId] = Schema[String].transform(wrap(_), unwrap)

    override def assertion = assert {
      !contains("__")
    }

    def unsafeMake(name: String): FlowId = wrap(name)

    def newRandom: ZIO[Any, Nothing, FlowId] =
      Random.nextUUID.map(rid => wrap(rid.toString))
  }

  type TransactionId = TransactionId.Type

  object TransactionId extends Newtype[String] {
    implicit val schema: Schema[TransactionId] = Schema[String].transform(wrap(_), unwrap)

    override def assertion = assert {
      !contains("__")
    }

    def fromCounter(counter: Int): TransactionId =
      wrap(counter.toString)

    def unsafeMake(name: String): TransactionId =
      wrap(name)
  }

}
