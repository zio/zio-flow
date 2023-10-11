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

package zio.flow.remote

import zio.schema.{DeriveSchema, Schema}

import java.util.Locale

private[flow] final case class LocaleParts(lang: String, country: Option[String], variant: Option[String]) {
  self =>
  def toJavaLocale: Either[String, Locale] = (country, variant) match {
    case (Some(c), Some(v)) => Right(new Locale.Builder().setLanguage(lang).setRegion(c).setVariant(v).build())
    case (Some(c), None)    => Right(new Locale.Builder().setLanguage(lang).setRegion(c).build())
    case (None, None)       => Right(new Locale.Builder().setLanguage(lang).build())
    case (None, Some(_))    => Left("failed to convert LocaleParts to a java.util.Locale")
  }
}

private[flow] object LocaleParts {
  implicit lazy val schema: Schema[LocaleParts] = DeriveSchema.gen

  def apply(jLocale: Locale): Right[Nothing, LocaleParts] =
    Right(new LocaleParts(jLocale.getLanguage, Option(jLocale.getCountry), Option(jLocale.getVariant)))

  implicit val localeSchema: Schema[Locale] = Schema[LocaleParts].transformOrFail(_.toJavaLocale, LocaleParts(_))
}
