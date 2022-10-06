package zio.flow

import zio.ZIO
import zio.schema.Schema

trait Configuration {
  def get[A: Schema](key: ConfigKey): ZIO[Any, Nothing, Option[A]]
}

object Configuration {}
