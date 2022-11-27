package zio.flow.runtime.internal

import zio._
import zio.flow.runtime.{ExecutorError, Timestamp}
import zio.flow.{ConfigKey, Configuration, RemoteContext, RemoteVariableName}
import zio.schema.{DynamicValue, Schema}
import zio.stm.TMap

final case class InMemoryRemoteContext(
  store: TMap[RemoteVariableName, DynamicValue],
  configuration: Configuration
) extends RemoteContext {
  override def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[Any, ExecutorError, Unit] =
    store.put(name, value).commit

  override def getVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[DynamicValue]] =
    store
      .get(name)
      .commit

  override def getLatestTimestamp(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[Timestamp]] =
    store.get(name).commit.map(_.map(_ => Timestamp(0L)))

  override def dropVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Unit] =
    store.delete(name).commit

  override def readConfig[A: Schema](key: ConfigKey): ZIO[Any, ExecutorError, Option[A]] =
    configuration.get[A](key)
}

object InMemoryRemoteContext {
  def make: ZIO[Any, Nothing, RemoteContext] =
    (for {
      vars          <- TMap.empty[RemoteVariableName, DynamicValue].commit
      configuration <- ZIO.service[Configuration]
    } yield InMemoryRemoteContext(vars, configuration)).provide(Configuration.inMemory)
}
