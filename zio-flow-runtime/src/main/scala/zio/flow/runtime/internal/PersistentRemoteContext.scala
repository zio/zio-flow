package zio.flow.runtime.internal

import zio.ZIO
import zio.flow.runtime.{DurableLog, ExecutorError, Timestamp}
import zio.flow.{ConfigKey, ExecutionEnvironment, RemoteContext, RemoteVariableName}
import zio.schema.{DynamicValue, Schema}
import zio.stm.TMap

import java.io.IOException

final case class PersistentRemoteContext(
  virtualClock: VirtualClock,
  remoteVariableStore: RemoteVariableKeyValueStore,
  executionEnvironment: ExecutionEnvironment,
  scope: RemoteVariableScope,
  scopeMap: TMap[RemoteVariableName, RemoteVariableScope]
) extends RemoteContext {

  override def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[Any, ExecutorError, Unit] =
    scopeMap.getOrElse(name, scope).commit.flatMap { variableScope =>
      virtualClock.current.flatMap { timestamp =>
        val serializedValue = executionEnvironment.serializer.serialize(value)
        remoteVariableStore
          .put(
            name,
            if (scope.transactionId.isDefined) scope else variableScope,
            serializedValue,
            timestamp
          )
          .unit
      }
    }

  override def getVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[DynamicValue]] =
    virtualClock.current.flatMap { timestamp =>
      remoteVariableStore
        .getLatest(name, scope, Some(timestamp))
        .flatMap {
          case Some((serializedValue, variableScope)) =>
            scopeMap.put(name, variableScope).commit.zipRight {
              ZIO
                .fromEither(executionEnvironment.deserializer.deserialize[DynamicValue](serializedValue))
                .map(Some(_))
                .orDieWith(msg => new IOException(s"Failed to deserialize remote variable $name: $msg"))
            }
          case None =>
            ZIO.none
        }
    }

  override def getLatestTimestamp(name: RemoteVariableName): ZIO[Any, ExecutorError, Option[Timestamp]] =
    remoteVariableStore
      .getLatestTimestamp(name, scope)
      .flatMap {
        case Some((timestamp, variableScope)) =>
          scopeMap.put(name, variableScope).commit.as(Some(timestamp))
        case None => ZIO.none
      }

  override def dropVariable(name: RemoteVariableName): ZIO[Any, ExecutorError, Unit] =
    remoteVariableStore.delete(name, scope, None)

  override def readConfig[A: Schema](key: ConfigKey): ZIO[Any, ExecutorError, Option[A]] =
    executionEnvironment.configuration.get[A](key)
}

object PersistentRemoteContext {
  def make(scope: RemoteVariableScope): ZIO[
    RemoteVariableKeyValueStore with ExecutionEnvironment with VirtualClock with DurableLog,
    Nothing,
    RemoteContext
  ] =
    for {
      virtualClock         <- ZIO.service[VirtualClock]
      remoteVariableStore  <- ZIO.service[RemoteVariableKeyValueStore]
      executionEnvironment <- ZIO.service[ExecutionEnvironment]
      scopeMap             <- TMap.empty[RemoteVariableName, RemoteVariableScope].commit
    } yield PersistentRemoteContext(virtualClock, remoteVariableStore, executionEnvironment, scope, scopeMap)
}
