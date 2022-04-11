package zio.flow.internal

import zio.{Chunk, UIO, ZIO}
import zio.flow.{RemoteContext, RemoteVariableName}
import zio.schema.DynamicValue
import zio.stm.TMap

trait RecordingRemoteContext {
  def getModifiedVariables: ZIO[Any, Nothing, Chunk[(RemoteVariableName, DynamicValue)]]
  def remoteContext: RemoteContext
}

object RecordingRemoteContext {
  def startRecording: ZIO[RemoteContext, Nothing, RecordingRemoteContext] =
    ZIO.service[RemoteContext].flatMap { outerRemoteContext =>
      TMap.empty[RemoteVariableName, DynamicValue].commit.map { cache =>
        new RecordingRemoteContext {
          override def getModifiedVariables: ZIO[Any, Nothing, Chunk[(RemoteVariableName, DynamicValue)]] =
            cache.toChunk.commit

          override val remoteContext: RemoteContext =
            new RemoteContext {
              override def setVariable(name: RemoteVariableName, value: DynamicValue): UIO[Unit] =
                cache.put(name, value).commit

              override def getVariable(name: RemoteVariableName): UIO[Option[DynamicValue]] =
                cache
                  .get(name)
                  .commit
                  .flatMap {
                    case Some(result) => ZIO.some(result)
                    case None         => outerRemoteContext.getVariable(name)
                  }
            }
        }
      }
    }
}
