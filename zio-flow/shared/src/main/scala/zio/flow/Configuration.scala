package zio.flow

import zio.{Ref, ZIO, ZLayer}
import zio.schema.Schema

trait Configuration {
  def get[A: Schema](key: ConfigKey): ZIO[Any, Nothing, Option[A]]
}

object Configuration {
  lazy val inMemory: ZLayer[Any, Nothing, Configuration] =
    ZLayer {
      for {
        ref <- Ref.make(Map.empty[ConfigKey, Any])
      } yield InMemory(ref)
    }

  private final case class InMemory(map: Ref[Map[ConfigKey, Any]]) extends Configuration {
    override def get[A: Schema](key: ConfigKey): ZIO[Any, Nothing, Option[A]] =
      map.get.map(_.get(key)).map(_.map(_.asInstanceOf[A]))
  }

}
