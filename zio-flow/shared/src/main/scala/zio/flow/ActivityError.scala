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

package zio.flow

import zio.schema.{Schema, TypeId}

/** Failure of running an [[Activity]] */
final case class ActivityError(failure: String, details: Option[Throwable])
object ActivityError {
  implicit lazy val schema: Schema[ActivityError] =
    Schema.CaseClass2[String, Option[Throwable], ActivityError](
      TypeId.parse("zio.flow.ActivityError"),
      field01 = Schema.Field[ActivityError, String](
        "failure",
        Schema[String],
        get0 = _.failure,
        set0 = (a, b) => a.copy(failure = b)
      ),
      field02 = Schema.Field[ActivityError, Option[Throwable]](
        "details",
        Schema.option(schemaThrowable),
        get0 = _.details,
        set0 = (a, b) => a.copy(details = b)
      ),
      ActivityError.apply
    )
}
