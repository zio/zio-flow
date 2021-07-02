package zio.flow

import zio.ZIO

/**
 * An `OperationExecutor` can execute operations, or fail trying.
 * 
 * TODO: Delete R from operation executor
 */
trait OperationExecutor[-R] {
  def execute[I, A](input: I, operation: Operation[I, A]): ZIO[R, ActivityError, A]
}
