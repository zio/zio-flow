package zio.flow.internal

import java.util.Locale
import zio.schema.{DeriveSchema, Schema}

private[flow] final case class LocaleParts(lang: String, country: Option[String], variant: Option[String]) {
  self =>
  def toJavaLocale: Either[String, Locale] = (country, variant) match {
    case (Some(c), Some(v)) => Right(new Locale(lang, c, v))
    case (Some(c), None)    => Right(new Locale(lang, c))
    case (None, None)       => Right(new Locale(lang))
    case (None, Some(_))    => Left("failed to convert LocaleParts to a java.util.Locale")
  }
}

private[flow] object LocaleParts {
  implicit def schema: Schema[LocaleParts] = DeriveSchema.gen

  def apply(jLocale: Locale): Right[Nothing, LocaleParts] =
    Right(new LocaleParts(jLocale.getLanguage, Option(jLocale.getCountry), Option(jLocale.getVariant)))

  implicit val localeSchema: Schema[Locale] = Schema[LocaleParts].transformOrFail(_.toJavaLocale, LocaleParts(_))
}
