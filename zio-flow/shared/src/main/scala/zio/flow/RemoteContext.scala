package zio.flow

import zio.{UIO, ULayer, ZIO, ZLayer}
import zio.stm.TMap

import java.util.UUID

trait RemoteContext {
  def setVariable[A](name: RemoteVariableName, value: A): UIO[Unit]
  def getVariable[A](name: RemoteVariableName): UIO[Option[A]]
}

object RemoteContext {
  def generateFreshVariableName: RemoteVariableName =
    RemoteVariableName(s"fresh_${UUID.randomUUID()}")
  def setVariable[A](name: RemoteVariableName, value: A): ZIO[RemoteContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setVariable(name, value))
  def getVariable[A](name: RemoteVariableName): ZIO[RemoteContext, Nothing, Option[A]] =
    ZIO.serviceWithZIO(_.getVariable(name))

  def inMemory: ULayer[RemoteContext] =
    ZLayer {
      TMap.empty[RemoteVariableName, Any].commit.map { store =>
        new RemoteContext {
          override def setVariable[A](name: RemoteVariableName, value: A): UIO[Unit] =
            store.put(name, value).commit

          override def getVariable[A](name: RemoteVariableName): UIO[Option[A]] =
            store.get(name).commit.map(_.map(_.asInstanceOf[A]))
        }
      }
    }
}
