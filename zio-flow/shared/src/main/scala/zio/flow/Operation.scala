/*
 * Copyright 2021 John A. De Goes and the ZIO Contributors
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

import zio.schema.Schema

sealed trait Operation[-R, +A]
object Operation {
  final case class Http[R, A](
    url: java.net.URI,
    method: String = "GET",
    headers: Map[String, String],
    inputSchema: Schema[R],
    outputSchema: Schema[A]
  ) extends Operation[R, A]
  final case class SendEmail(
    server: String,
    port: Int
  ) extends Operation[EmailRequest, Unit]
}

final case class EmailRequest(
  to: List[String],
  from: Option[String],
  cc: List[String],
  bcc: List[String],
  body: String
)
