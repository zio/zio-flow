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

import zio.schema._
import zio.flow.operation.http.API

sealed trait Operation[-R, +A] {
  val inputSchema: Schema[_ >: R]
  val resultSchema: Schema[_ <: A]
}

object Operation {
  final case class Http[R, A](
    host: String,
    api: zio.flow.operation.http.API[R, A]
  ) extends Operation[R, A] {
    override val inputSchema: Schema[_ >: R]  = api.requestInput.schema
    override val resultSchema: Schema[_ <: A] = api.outputSchema
  }

  object Http {
    private val typeId: TypeId = TypeId.parse("zio.flow.Operation.Http")

    def schema[R, A]: Schema[Http[R, A]] = Schema.CaseClass2[String, API[R, A], Http[R, A]](
      typeId,
      Schema.Field("host", Schema[String]),
      Schema.Field("api", API.schema[R, A]),
      (host, api) => Http(host, api),
      _.host,
      _.api
    )

    def schemaCase[R, A]: Schema.Case[Http[R, A], Operation[R, A]] =
      Schema.Case("Http", schema[R, A], _.asInstanceOf[Http[R, A]])
  }

  private val typeId: TypeId = TypeId.parse("zio.flow.Operation")

  implicit def schema[R, A]: Schema[Operation[R, A]] =
    Schema.EnumN(
      typeId,
      CaseSet
        .Cons(Http.schemaCase[R, A], CaseSet.Empty[Operation[R, A]]())
    )
}

final case class EmailRequest(
  to: List[String],
  from: Option[String],
  cc: List[String],
  bcc: List[String],
  body: String
)

object EmailRequest {
  implicit val emailRequestSchema: Schema[EmailRequest] = DeriveSchema.gen[EmailRequest]
}
