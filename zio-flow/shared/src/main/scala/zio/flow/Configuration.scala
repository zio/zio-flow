package zio.flow

import zio.{Ref, ZIO, ZLayer}
import zio.schema.Schema
import zio.System

trait Configuration {
  def get[A: Schema](key: ConfigKey): ZIO[Any, Nothing, Option[A]]
  def set[A: Schema](key: ConfigKey, value: A): ZIO[Any, Nothing, Unit]
}

object Configuration {
  val any: ZLayer[Configuration, Nothing, Configuration] = ZLayer.service[Configuration]

  def get[A: Schema](key: ConfigKey): ZIO[Configuration, Nothing, Option[A]] =
    ZIO.serviceWithZIO(_.get[A](key))

  def set[A: Schema](key: ConfigKey, value: A): ZIO[Configuration, Nothing, Unit] =
    ZIO.serviceWithZIO(_.set(key, value))

  lazy val inMemory: ZLayer[Any, Nothing, Configuration] =
    ZLayer {
      for {
        ref <- Ref.make(Map.empty[ConfigKey, Any])
      } yield InMemory(ref)
    }

  def fromEnvironment(mapping: (ConfigKey, String)*): ZLayer[Any, SecurityException, Configuration] =
    ZLayer {
      for {
        initial <- ZIO.foldLeft(mapping)(Map.empty[ConfigKey, Any]) { case (map, (key, envName)) =>
                     System.env(envName).map {
                       case Some(value) => map.updated(key, value)
                       case None        => map
                     }
                   }
        ref <- Ref.make(initial)
      } yield InMemory(ref)
    }

  private final case class InMemory(map: Ref[Map[ConfigKey, Any]]) extends Configuration {
    override def get[A: Schema](key: ConfigKey): ZIO[Any, Nothing, Option[A]] =
      map.get.map(_.get(key)).map(_.map(_.asInstanceOf[A]))

    override def set[A: Schema](key: ConfigKey, value: A): ZIO[Any, Nothing, Unit] =
      map.update(_.updated(key, value))
  }
}
