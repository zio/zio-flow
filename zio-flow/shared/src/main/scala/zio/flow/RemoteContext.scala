package zio.flow

import zio.flow.internal._
import zio.schema.DynamicValue
import zio.stm.TMap
import zio.{UIO, ZIO}

import java.io.IOException

trait RemoteContext {
  def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit]
  def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]]
  def getLatestTimestamp(name: RemoteVariableName): UIO[Option[Timestamp]]
}

object RemoteContext {

  def setVariable(name: RemoteVariableName, value: DynamicValue): ZIO[RemoteContext, Nothing, Unit] =
    ZIO.serviceWithZIO(_.setVariable(name, value))
  def getVariable(name: RemoteVariableName): ZIO[RemoteContext, Nothing, Option[DynamicValue]] =
    ZIO.serviceWithZIO(_.getVariable(name))

  private final case class InMemory(
    store: TMap[RemoteVariableName, DynamicValue]
  ) extends RemoteContext {
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
    (for {
      vars <- TMap.empty[RemoteVariableName, DynamicValue]
    } yield InMemory(vars)).commit

  private final case class Persistent(
    virtualClock: VirtualClock,
    remoteVariableStore: RemoteVariableKeyValueStore,
    executionEnvironment: ExecutionEnvironment,
    scope: RemoteVariableScope,
    scopeMap: TMap[RemoteVariableName, RemoteVariableScope]
  ) extends RemoteContext {

    override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
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
            .orDie // TODO: rethink/cleanup error handling
            .unit
        }
      }

    override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
      virtualClock.current.flatMap { timestamp =>
        remoteVariableStore
          .getLatest(name, scope, Some(timestamp))
          .orDie // TODO: rethink/cleanup error handling
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

    override def getLatestTimestamp(name: RemoteVariableName): UIO[Option[Timestamp]] =
      remoteVariableStore
        .getLatestTimestamp(name, scope)
        .orDie // TODO: rethink/cleanup error handling
        .flatMap {
          case Some((timestamp, variableScope)) =>
            scopeMap.put(name, variableScope).commit.as(Some(timestamp))
          case None => ZIO.none
        }
  }

  def persistent(
    scope: RemoteVariableScope
  ): ZIO[
    RemoteVariableKeyValueStore with ExecutionEnvironment with VirtualClock with DurableLog,
    Nothing,
    RemoteContext
  ] =
    for {
      virtualClock         <- ZIO.service[VirtualClock]
      remoteVariableStore  <- ZIO.service[RemoteVariableKeyValueStore]
      executionEnvironment <- ZIO.service[ExecutionEnvironment]
      scopeMap             <- TMap.empty[RemoteVariableName, RemoteVariableScope].commit
    } yield Persistent(virtualClock, remoteVariableStore, executionEnvironment, scope, scopeMap)
}
