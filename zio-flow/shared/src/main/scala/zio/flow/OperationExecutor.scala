/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow

import zio.{ZEnvironment, ZIO}

/**
 * An `OperationExecutor` can execute operations, or fail trying.
 *
 * TODO: Delete R from operation executor
 */
trait OperationExecutor[-R] { self =>
  def execute[I, A](input: I, operation: Operation[I, A]): ZIO[R, ActivityError, A]

  def provideEnvironment(env: ZEnvironment[R]): OperationExecutor[Any] =
    new OperationExecutor[Any] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Any, ActivityError, A] =
        self.execute(input, operation).provideEnvironment(env)
    }
}
