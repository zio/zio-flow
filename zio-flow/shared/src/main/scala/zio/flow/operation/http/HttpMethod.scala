/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

import zio.schema.DeriveSchema
import zio.schema.Schema

sealed trait HttpMethod extends Product with Serializable { self =>
  def toZioHttpMethod: zhttp.http.Method =
    self match {
      case HttpMethod.GET    => zhttp.http.Method.GET
      case HttpMethod.POST   => zhttp.http.Method.POST
      case HttpMethod.PATCH  => zhttp.http.Method.PATCH
      case HttpMethod.PUT    => zhttp.http.Method.PUT
      case HttpMethod.DELETE => zhttp.http.Method.DELETE
    }
}

object HttpMethod {

  val schema: Schema[HttpMethod] = DeriveSchema.gen[HttpMethod]

  case object GET    extends HttpMethod
  case object POST   extends HttpMethod
  case object PATCH  extends HttpMethod
  case object PUT    extends HttpMethod
  case object DELETE extends HttpMethod
}
