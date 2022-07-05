package zio.flow.internal

import zio.flow.{RemoteContext, RemoteVariableName}
import zio.schema.DynamicValue
import zio.stm.TMap
import zio.{Chunk, UIO, ZIO}

trait RecordingRemoteContext {
  def getModifiedVariables: ZIO[Any, Nothing, Chunk[(RemoteVariableName, DynamicValue)]]
  def remoteContext: RemoteContext
  def commitContext: RemoteContext
  def virtualClock: VirtualClock
}

object RecordingRemoteContext {
  def startRecording: ZIO[RemoteContext with VirtualClock, Nothing, RecordingRemoteContext] =
    ZIO.service[RemoteContext].flatMap { outerRemoteContext =>
      ZIO.service[VirtualClock].flatMap { vclock =>
        TMap.empty[RemoteVariableName, DynamicValue].commit.map { cache =>
          new RecordingRemoteContext {
            override def commitContext: RemoteContext = outerRemoteContext

            override def getModifiedVariables: ZIO[Any, Nothing, Chunk[(RemoteVariableName, DynamicValue)]] =
              cache.toChunk.commit

            override val remoteContext: RemoteContext =
              new RemoteContext {
                override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
                  cache.put(name, value).commit

                override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
                  cache.get(name).commit.flatMap {
                    case Some(result) => ZIO.some(result)
                    case None         => outerRemoteContext.getVariable(name)
                  }

                override def getLatestTimestamp(name: RemoteVariableName): UIO[Option[Timestamp]] =
                  outerRemoteContext.getLatestTimestamp(name)
              }

            override def virtualClock: VirtualClock = vclock
          }
        }
      }
    }
}
