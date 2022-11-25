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

package zio.flow.server.common

import zhttp.http._
import zio.ZIO
import zio.json.{JsonDecoder, JsonEncoder}
import zio.schema.Schema
import zio.schema.codec.JsonCodec

import java.nio.charset.StandardCharsets

trait Api {
  protected def jsonCodecResponse[T: JsonEncoder](body: T, status: Status = Status.Ok): Response =
    Response(
      body = Body.fromCharSequence(implicitly[JsonEncoder[T]].encodeJson(body, None)),
      headers = Headers(HeaderNames.contentType, HeaderValues.applicationJson),
      status = status
    )

  protected def jsonResponse[T: Schema](body: T, status: Status = Status.Ok): Response =
    jsonCodecResponse(body, status)(JsonCodec.jsonEncoder(implicitly[Schema[T]]))

  protected def jsonCodecBody[T: JsonDecoder](request: Request): ZIO[Any, Throwable, T] =
    for {
      payload <- request.body.asChunk
      result <- ZIO
                  .fromEither(
                    implicitly[JsonDecoder[T]].decodeJson(new String(payload.toArray, StandardCharsets.UTF_8))
                  )
                  .mapError(str => new IllegalArgumentException(str))
    } yield result

  protected def jsonBody[T: Schema](request: Request): ZIO[Any, Throwable, T] =
    jsonCodecBody[T](request)(JsonCodec.jsonDecoder(implicitly[Schema[T]]))
}
