package zio.flow.mock

import zio.{Clock, Ref, Scope, ZIO}
import zio.flow.{ActivityError, Operation, OperationExecutor}

case class MockedOperationExecutor private (mocks: Ref[MockedOperation]) extends OperationExecutor[Clock] {
  override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Clock, ActivityError, A] =
    mocks.modify { mock =>
      mock.matchOperation(operation, input)
    }.flatMap {
      case Some(MockedOperation.Match(result, duration)) =>
        for {
          _ <- ZIO.logInfo(s"Simulating operation $operation with input $input")
          _ <- Clock.sleep(duration)
        } yield result
      case None => ZIO.fail(ActivityError(s"Operation $operation not found", None))
    }
}

object MockedOperationExecutor {
  def make(mock: MockedOperation): ZIO[Scope with Clock, Nothing, MockedOperationExecutor] =
    Ref.make(mock).flatMap { ref =>
      val opExecutor = new MockedOperationExecutor(ref)
      ZIO.addFinalizer {
        ref.get.flatMap { lastMock =>
          ZIO
            .dieMessage(s"Some of the mocked operation expectations did not met: $lastMock")
            .unless(
              lastMock == MockedOperation.Empty ||
                lastMock.isInstanceOf[MockedOperation.Repeated]
            )
        }
      }.as(opExecutor)
    }
}
