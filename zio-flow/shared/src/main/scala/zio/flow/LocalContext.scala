package zio.flow

import zio.schema.DynamicValue
import zio.stm.TMap
import zio.{UIO, ZIO, ZLayer}

import java.util.UUID

trait LocalContext {
  def storeVariable(name: Remote.Local[_], value: DynamicValue): UIO[Unit]
  def getVariable(name: Remote.Local[_]): UIO[Option[DynamicValue]]
  def getAllVariables: UIO[Map[Remote.Local[_], DynamicValue]]
}

object LocalContext {
  def generateFreshVariableName: LocalVariableName =
    LocalVariableName(UUID.randomUUID())

  def generateFreshBinding: BindingName =
    BindingName(UUID.randomUUID())

  def storeVariable(name: Remote.Local[_], value: DynamicValue): ZIO[LocalContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.storeVariable(name, value))
  def getVariable(name: Remote.Local[_]): ZIO[LocalContext, Nothing, Option[DynamicValue]] =
    ZIO.serviceWithZIO(_.getVariable(name))
  def getAllVariables: ZIO[LocalContext, Nothing, Map[Remote.Local[_], DynamicValue]] =
    ZIO.serviceWithZIO(_.getAllVariables)

  private final case class InMemory(
    store: TMap[Remote.Local[_], DynamicValue]
  ) extends LocalContext {
    override def storeVariable(name: Remote.Local[_], value: DynamicValue): UIO[Unit] =
      store.put(name, value).commit

    override def getVariable(name: Remote.Local[_]): UIO[Option[DynamicValue]] =
      store.get(name).commit

    override def getAllVariables: UIO[Map[Remote.Local[_], DynamicValue]] =
      store.toMap.commit
  }

  def inMemory: ZLayer[Any, Nothing, LocalContext] =
    ZLayer {
      for {
        vars <- TMap.empty[Remote.Local[_], DynamicValue].commit
      } yield InMemory(vars)
    }

}
