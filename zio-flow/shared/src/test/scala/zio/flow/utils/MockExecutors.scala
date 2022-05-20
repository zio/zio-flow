package zio.flow.utils

import zio._
import zio.flow.internal.{DurableLog, KeyValueStore, PersistentExecutor, ZFlowExecutor}
import zio.flow.mock.{MockedOperation, MockedOperationExecutor}
import zio.flow.serialization.{Deserializer, Serializer}

object MockExecutors {
  def persistent(
    mockedOperations: MockedOperation = MockedOperation.Empty
  ): ZIO[Scope with Console with Clock with DurableLog with KeyValueStore, Nothing, ZFlowExecutor] =
    ZIO.service[Clock].flatMap { clock =>
      MockedOperationExecutor.make(mockedOperations).flatMap { operationExecutor =>
        PersistentExecutor
          .make(
            operationExecutor.provideEnvironment(ZEnvironment(clock)),
            Serializer.json,
            Deserializer.json
          )
          .build
          .map(_.get[ZFlowExecutor])
      }
    }
}
