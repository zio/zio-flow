package zio.flow

import zio.flow.internal.{KeyValueStore, Namespaces, VirtualClock}
import zio.schema.DynamicValue
import zio.stm.TMap
import zio.{Chunk, UIO, ZIO}

import java.io.IOException
import java.nio.charset.StandardCharsets
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

  def inMemory: ZIO[Any, Nothing, RemoteContext] =
    TMap.empty[RemoteVariableName, DynamicValue].commit.map { store =>
      new RemoteContext {
        override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
          store.put(name, value).commit

        override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
          store
            .get(name)
            .commit
      }
    }

  def persistent(
    flowId: FlowId
  ): ZIO[KeyValueStore with ExecutionEnvironment with VirtualClock, Nothing, RemoteContext] =
    ZIO.service[VirtualClock].flatMap { virtualClock =>
      ZIO.service[KeyValueStore].flatMap { kvStore =>
        ZIO.service[ExecutionEnvironment].map { execEnv =>
          new RemoteContext {
            private def key(name: RemoteVariableName): Chunk[Byte] =
              Chunk.fromArray(name.prefixedBy(flowId).getBytes(StandardCharsets.UTF_8))

            override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
              virtualClock.current.flatMap { timestamp =>
                val serializedValue = execEnv.serializer.serialize(value)
                kvStore
                  .put(
                    Namespaces.variables,
                    key(name),
                    serializedValue,
                    timestamp
                  )
                  .orDie // TODO: rethink/cleanup error handling
                  .unit
              }

            override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
              virtualClock.current.flatMap { timestamp =>
                kvStore
                  .getLatest(
                    Namespaces.variables,
                    key(name),
                    Some(timestamp)
                  )
                  .orDie // TODO: rethink/cleanup error handling
                  .flatMap {
                    case Some(serializedValue) =>
                      ZIO
                        .fromEither(execEnv.deserializer.deserialize[DynamicValue](serializedValue))
                        .map(Some(_))
                        .orDieWith(msg =>
                          new IOException(s"Failed to deserialize remote variable $name: $msg")
                        ) // TODO: rethink/cleanup error handling
                    case None =>
                      ZIO.none
                  }
              }
          }
        }
      }
    }
}
