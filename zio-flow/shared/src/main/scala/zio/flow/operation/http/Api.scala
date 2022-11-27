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

import zhttp.http.{Headers => _, Path => _}
import zio.schema.{Schema, TypeId}
import zio.flow.serialization.FlowSchemaAst

/**
 *   - Input and Output as Schemas.
 *   - Dynamically decide response format based upon Request Header
 */
final case class API[Input, Output](
  method: HttpMethod,
  requestInput: RequestInput[Input], // Path / QueryParams / Headers / Body
  outputSchema: Schema[Output]
) { self =>
  type Id

  def query[A](queryParams: Query[A])(implicit
    zipper: Zipper[Input, A]
  ): API[zipper.Out, Output] =
    copy(requestInput = requestInput ++ queryParams)

  def header[A](headers: Header[A])(implicit zipper: Zipper[Input, A]): API[zipper.Out, Output] =
    copy(requestInput = requestInput ++ headers)

  def input[A](implicit schema: Schema[A], zipper: Zipper[Input, A]): API[zipper.Out, Output] =
    copy(requestInput = requestInput ++ Body(schema, ContentType.json))

  def input[A](
    contentType: ContentType
  )(implicit schema: Schema[A], zipper: Zipper[Input, A]): API[zipper.Out, Output] =
    copy(requestInput = requestInput ++ Body(schema, contentType))

  def output[Output2](implicit schema: Schema[Output2]): API[Input, Output2] =
    copy(outputSchema = schema)
}

object API {
  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.API")

  def schema[Input, Output]: Schema[API[Input, Output]] =
    Schema.CaseClass3[HttpMethod, RequestInput[Input], FlowSchemaAst, API[Input, Output]](
      typeId,
      Schema.Field("method", HttpMethod.schema, get0 = _.method, set0 = (a, v) => a.copy(method = v)),
      Schema.Field(
        "requestInput",
        RequestInput.schema[Input],
        get0 = _.requestInput,
        set0 = (a, v) => a.copy(requestInput = v)
      ),
      Schema.Field(
        "outputSchema",
        FlowSchemaAst.schema,
        get0 = api => FlowSchemaAst.fromSchema(api.outputSchema),
        set0 = (a, v) => a.copy(outputSchema = v.toSchema)
      ),
      (method, requestInput, outputSchema) => API(method, requestInput, outputSchema.toSchema[Output])
    )

  type WithId[Input, Output, Id0] = API[Input, Output] { type Id = Id0 }

  trait NotUnit[A]

  object NotUnit {
    implicit def notUnit[A]: NotUnit[A] = new NotUnit[A] {}

    implicit val notUnitUnit1: NotUnit[Unit] = new NotUnit[Unit] {}
    implicit val notUnitUnit2: NotUnit[Unit] = new NotUnit[Unit] {}
  }

  /**
   * Creates an API for DELETE request at the given path.
   */
  def delete[A](path: Path[A] = ""): API[A, Unit] =
    method(HttpMethod.DELETE, path)

  /**
   * Creates an API for a GET request at the given path.
   */
  def get[A](path: Path[A] = ""): API[A, Unit] =
    method(HttpMethod.GET, path)

  /**
   * Creates an API for a POST request at the given path.
   */
  def post[A](path: Path[A] = ""): API[A, Unit] =
    method(HttpMethod.POST, path)

  /**
   * Creates an API for a PUT request at the given path.
   */
  def put[A](path: Path[A] = ""): API[A, Unit] =
    method(HttpMethod.PUT, path)

  /**
   * Creates an API for a PATCH request at the given path.
   */
  def patch[A](path: Path[A] = ""): API[A, Unit] =
    method(HttpMethod.PATCH, path)

  /**
   * Creates an API with the given method and path.
   */
  private def method[Params](method: HttpMethod, path: Path[Params]): API[Params, Unit] =
    API(method, path, Schema[Unit])

}
