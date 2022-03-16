package zio.flow

import zio.schema.DynamicValue
import zio.{UIO, ULayer, ZIO, ZLayer}
import zio.stm.TMap

import java.util.UUID

trait RemoteContext {
  def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit]
  def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]]
}

object RemoteContext {
  def generateFreshVariableName: RemoteVariableName =
    RemoteVariableName(s"fresh_${UUID.randomUUID()}")
  def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[RemoteContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setVariable(name, value))
  def getVariable(name: RemoteVariableName): ZIO[RemoteContext, Nothing, Option[DynamicValue]] =
    ZIO.serviceWithZIO(_.getVariable(name))

  def inMemory: ULayer[RemoteContext] =
    ZLayer {
      TMap.empty[RemoteVariableName, DynamicValue].commit.map { store =>
        new RemoteContext {
          override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
//            ZIO.debug(s"*** STORED VARIABLE $name: $value") *>
            store.put(name, value).commit

          override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
            store
              .get(name)
              .commit
//              .tap(v => ZIO.debug(s"*** GETTING VARIABLE $name => $v"))
        }
      }
    }
}
