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

package zio.flow.operation.http

import zio.ZIO
import zhttp.service.EventLoopGroup
import zhttp.service.ChannelFactory
import zio.schema.codec.JsonCodec

final class APIOps[Input, Output: API.NotUnit, Id](
  val self: API.WithId[Input, Output, Id]
) {
  def call(host: String)(params: Input): ZIO[EventLoopGroup with ChannelFactory, Throwable, Output] =
    ClientInterpreter.interpret(host)(self)(params).flatMap(_.body).flatMap { string =>
      JsonCodec.decode(self.outputSchema)(string) match {
        case Left(err)    => ZIO.fail(new Error(s"Could not parse response: $err"))
        case Right(value) => ZIO.succeed(value)
      }
    }
}

final class APIOpsUnit[Input, Id](val self: API.WithId[Input, Unit, Id]) {
  def call(host: String)(params: Input): ZIO[EventLoopGroup with ChannelFactory, Throwable, Unit] =
    ClientInterpreter.interpret(host)(self)(params).unit
}
