package zio.flow.mock

import zio.{Ref, ZIO}
import zio.flow.{ActivityError, Operation, OperationExecutor}

case class MockedOperationExecutor private (mocks: Ref[MockedOperation]) extends OperationExecutor[Any] {
  override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Any, ActivityError, A] =
    mocks.modify { mock =>
      mock.matchOperation(operation, input)
    }.flatMap {
      case Some(result) => ZIO.succeed(result)
      case None         => ZIO.fail(ActivityError(s"Operation $operation not found", None))
    }
}

object MockedOperationExecutor {
  def make(mock: MockedOperation): ZIO[Any, Nothing, MockedOperationExecutor] =
    Ref.make(mock).map(new MockedOperationExecutor(_))
}
