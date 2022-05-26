package zio.flow.internal

import zio.flow.{FlowId, RemoteVariableName, TransactionId}
import zio.stm.TSet
import zio.{Chunk, IO, ZIO, ZLayer}

import java.io.IOException
import java.nio.charset.StandardCharsets

final case class RemoteVariableKeyValueStore(
  keyValueStore: KeyValueStore,
  readVariables: TSet[(RemoteVariableName, RemoteVariableScope)]
) {
  def put(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    value: Chunk[Byte],
    timestamp: Timestamp
  ): IO[IOException, Boolean] =
    ZIO.logDebug(s"Storing variable ${RemoteVariableName.unwrap(name)} in scope $scope with timestamp $timestamp") *>
      keyValueStore.put(Namespaces.variables, key(getScopePrefix(scope), name), value, timestamp)

  def getLatest(
    name: RemoteVariableName,
    scope: RemoteVariableScope,
    before: Option[Timestamp]
  ): IO[IOException, Option[(Chunk[Byte], RemoteVariableScope)]] =
    keyValueStore.getLatest(Namespaces.variables, key(getScopePrefix(scope), name), before).flatMap {
      case Some(value) =>
        ZIO.logDebug(s"Read variable ${RemoteVariableName.unwrap(name)} from scope $scope") *>
          readVariables
            .put((name, scope))
            .as(Some((value, scope)))
            .commit
      case None =>
        scope.parentScope match {
          case Some(parentScope) =>
            getLatest(name, parentScope, before)
          case None =>
            ZIO.none
        }
    }

  def getLatestTimestamp(
    name: RemoteVariableName,
    scope: RemoteVariableScope
  ): IO[IOException, Option[(Timestamp, RemoteVariableScope)]] =
    keyValueStore.getLatestTimestamp(Namespaces.variables, key(getScopePrefix(scope), name)).flatMap {
      case Some(value) =>
        ZIO.logDebug(s"Read latest timestamp of variable ${RemoteVariableName.unwrap(name)} from scope $scope") *>
          ZIO.some((value, scope))
      case None =>
        scope.parentScope match {
          case Some(parentScope) =>
            getLatestTimestamp(name, parentScope)
          case None =>
            ZIO.none
        }
    }

  def delete(name: RemoteVariableName, scope: RemoteVariableScope): IO[IOException, Unit] =
    ZIO.logDebug(s"Deleting ${RemoteVariableName.unwrap(name)} from scope $scope") *>
      keyValueStore.delete(Namespaces.variables, key(getScopePrefix(scope), name))

  def getReadVariables: IO[Nothing, Set[(RemoteVariableName, RemoteVariableScope)]] =
    readVariables.toSet.commit

  private def key(prefix: String, name: RemoteVariableName): Chunk[Byte] =
    Chunk.fromArray((prefix + RemoteVariableName.unwrap(name)).getBytes(StandardCharsets.UTF_8))

  private def getScopePrefix(scope: RemoteVariableScope): String =
    scope match {
      case RemoteVariableScope.TopLevel(flowId) =>
        FlowId.unwrap(flowId) + "__"
      case RemoteVariableScope.Fiber(flowId, parent) =>
        FlowId.unwrap(flowId) + "__"
      case RemoteVariableScope.Transactional(parent, transaction) =>
        getScopePrefix(parent) + "tx" ++ TransactionId.unwrap(transaction) + "__"
    }
}

object RemoteVariableKeyValueStore {
  val live: ZLayer[KeyValueStore, Nothing, RemoteVariableKeyValueStore] = ZLayer {
    for {
      kvStore       <- ZIO.service[KeyValueStore]
      readVariables <- TSet.empty[(RemoteVariableName, RemoteVariableScope)].commit
    } yield new RemoteVariableKeyValueStore(kvStore, readVariables)
  }

  def getReadVariables: ZIO[RemoteVariableKeyValueStore, Nothing, Set[(RemoteVariableName, RemoteVariableScope)]] =
    ZIO.serviceWithZIO(_.getReadVariables)
}
