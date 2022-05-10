package zio.flow

import zio.flow.internal.{KeyValueStore, Namespaces, RemoteVariableScope, Timestamp, VirtualClock}
import zio.schema.DynamicValue
import zio.stm.TMap
import zio.{Chunk, NonEmptyChunk, UIO, ZIO}

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

trait RemoteContext {
  def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit]
  def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]]
  def getLatestTimestamp(name: RemoteVariableName): UIO[Option[Timestamp]]
}

object RemoteContext {
  def generateFreshVariableName: RemoteVariableName =
    RemoteVariableName(s"fresh_${UUID.randomUUID()}")
  def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[RemoteContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setVariable(name, value))
  def getVariable(name: RemoteVariableName): ZIO[RemoteContext, Nothing, Option[DynamicValue]] =
    ZIO.serviceWithZIO(_.getVariable(name))

  private final case class InMemory(store: TMap[RemoteVariableName, DynamicValue]) extends RemoteContext {
    override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
      store.put(name, value).commit

    override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
      store
        .get(name)
        .commit

    override def getLatestTimestamp(name: RemoteVariableName): UIO[Option[Timestamp]] =
      store.get(name).commit.map(_.map(_ => Timestamp(0L)))
  }

  def inMemory: ZIO[Any, Nothing, RemoteContext] =
    TMap.empty[RemoteVariableName, DynamicValue].commit.map(InMemory.apply)

  private final case class Persistent(
    virtualClock: VirtualClock,
    kvStore: KeyValueStore,
    executionEnvironment: ExecutionEnvironment,
    scopeStack: NonEmptyChunk[RemoteVariableScope]
  ) extends RemoteContext {
    private def key(name: RemoteVariableName, scope: RemoteVariableScope): Chunk[Byte] =
      Chunk.fromArray(name.prefixedBy(scope.flowId, scope.transactionId).getBytes(StandardCharsets.UTF_8))

    override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
      virtualClock.current.flatMap { timestamp =>
        val serializedValue = executionEnvironment.serializer.serialize(value)
        kvStore
          .put(
            Namespaces.variables,
            key(name, scopeStack.head),
            serializedValue,
            timestamp
          )
          .orDie // TODO: rethink/cleanup error handling
          .unit
      }

    override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
      virtualClock.current.flatMap { timestamp =>
        tryGetVariable(name, scopeStack, timestamp)
      }

    override def getLatestTimestamp(name: RemoteVariableName): UIO[Option[Timestamp]] =
      kvStore
        .getLatestTimestamp(Namespaces.variables, key(name, scopeStack.head))
        .orDie // TODO: rethink/cleanup error handling

    private def tryGetVariable(
      name: RemoteVariableName,
      scopes: NonEmptyChunk[RemoteVariableScope],
      timestamp: Timestamp
    ): UIO[Option[DynamicValue]] =
      kvStore
        .getLatest(
          Namespaces.variables,
          key(name, scopes.head),
          Some(timestamp)
        )
        .orDie // TODO: rethink/cleanup error handling
        .flatMap {
          case Some(serializedValue) =>
            ZIO
              .fromEither(executionEnvironment.deserializer.deserialize[DynamicValue](serializedValue))
              .map(Some(_))
              .orDieWith(msg =>
                new IOException(s"Failed to deserialize remote variable $name: $msg")
              ) // TODO: rethink/cleanup error handling
          case None =>
            NonEmptyChunk.fromChunk(scopes.tail) match {
              case Some(remainingScopes) =>
                tryGetVariable(name, remainingScopes, timestamp)
              case None => ZIO.none
            }
        }
  }

  def persistent(
    scopeStack: NonEmptyChunk[RemoteVariableScope]
  ): ZIO[KeyValueStore with ExecutionEnvironment with VirtualClock, Nothing, RemoteContext] =
    ZIO.service[VirtualClock].flatMap { virtualClock =>
      ZIO.service[KeyValueStore].flatMap { kvStore =>
        ZIO.service[ExecutionEnvironment].map { executionEnvironment =>
          Persistent(virtualClock, kvStore, executionEnvironment, scopeStack)
        }
      }
    }
}
