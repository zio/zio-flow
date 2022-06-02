package zio.flow.operation.http

import zhttp.http.{Headers => _, Path => _}
import zio.json._
import zio.schema.Schema
import zio.Unzippable

/**   - Input and Output as Schemas.
  *   - Support wide range of Codecs including Json
  *     - Dynamically decide response format based upon Request Header
  */
final case class API[Input, Output](
    method: HttpMethod,
    requestInput: RequestInput[Input], // Path / QueryParams / Headers / Body
    outputCodec: JsonCodec[Output],
    outputSchema: Schema[Output]
) { self =>
  type Id

  def query[A](queryParams: Query[A])(implicit
      unzippable: Unzippable[Input, A]
  ): API[unzippable.In, Output] =
    copy(requestInput = requestInput ++ queryParams)

  def header[A](headers: Header[A])(implicit unzippable: Unzippable[Input, A]): API[unzippable.In, Output] =
    copy(requestInput = requestInput ++ headers)

  def input[A](implicit codec: JsonCodec[A], schema: Schema[A], unzippable: Unzippable[Input, A]): API[unzippable.In, Output] =
    copy(requestInput = requestInput ++ Body(codec, schema))

  def output[Output2](implicit codec: JsonCodec[Output2], schema: Schema[Output2]): API[Input, Output2] =
    copy(outputCodec = codec, outputSchema = schema)
}

object API {

  type WithId[Input, Output, Id0] = API[Input, Output] { type Id = Id0 }

  trait NotUnit[A]

  object NotUnit {
    implicit def notUnit[A]: NotUnit[A] = new NotUnit[A] {}

    implicit val notUnitUnit1: NotUnit[Unit] = new NotUnit[Unit] {}
    implicit val notUnitUnit2: NotUnit[Unit] = new NotUnit[Unit] {}
  }

  /** Creates an API for DELETE request at the given path.
    */
  def delete[A](path: Path[A]): API[A, Unit] =
    method(HttpMethod.DELETE, path)

  /** Creates an API for a GET request at the given path.
    */
  def get[A](path: Path[A]): API[A, Unit] =
    method(HttpMethod.GET, path)

  /** Creates an API for a POST request at the given path.
    */
  def post[A](path: Path[A]): API[A, Unit] =
    method(HttpMethod.POST, path)

  /** Creates an API for a PUT request at the given path.
    */
  def put[A](path: Path[A]): API[A, Unit] =
    method(HttpMethod.PUT, path)

  /** Creates an API with the given method and path.
    */
  private def method[Params](method: HttpMethod, path: Path[Params]): API[Params, Unit] =
    API(method, path, unitCodec, Schema[Unit])

}
