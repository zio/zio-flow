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

package zio.flow.mock

import zio.flow.Operation
import zio.test.Assertion.anything
import zio._
import zio.flow.mock.MockedOperation.Match
import zio.schema.{DynamicValue, Schema}

trait MockedOperation { self =>
  def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation)

  def andThen(other: MockedOperation): MockedOperation =
    MockedOperation.Then(self, other)
  def ++(other: MockedOperation): MockedOperation =
    andThen(other)

  def orElse(other: MockedOperation): MockedOperation =
    MockedOperation.Or(self, other)
  def |(other: MockedOperation): MockedOperation =
    orElse(other)

  def repeated(atMost: Int = Int.MaxValue): MockedOperation =
    MockedOperation.Repeated(self, atMost)
}

object MockedOperation {
  case class Match[A](result: A, delay: Duration)

  case object Empty extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation) =
      (None, Empty)
  }
  final case class Http[R, A: Schema](
    urlMatcher: zio.test.Assertion[String] = anything,
    methodMatcher: zio.test.Assertion[String] = anything,
    headersMatcher: zio.test.Assertion[Map[String, String]] = anything,
    inputMatcher: zio.test.Assertion[R] = anything,
    result: () => A,
    duration: Duration = Duration.Zero
  ) extends MockedOperation {
    override def matchOperation[R1, A1](operation: Operation[R1, A1], input: R1): (Option[Match[A1]], MockedOperation) =
      operation match {
        case Operation.Http(url, api) =>
          // TODO: check R1 and A1 types too
          // TODO: check headers as well
          val m =
            urlMatcher.run(url) && methodMatcher.run(api.method.toString()) && inputMatcher.run(
              input.asInstanceOf[R]
            )
          if (m.isSuccess) {
            val typedSchema = implicitly[Schema[A]]
            val value       = result()
            val reencoded   = DynamicValue.fromSchemaAndValue(typedSchema, value).toTypedValue(operation.resultSchema)
            (
              Some(
                Match(
                  reencoded.getOrElse(
                    throw new IllegalStateException(
                      s"Failed to reencode value $value with schema ${operation.resultSchema}"
                    )
                  ),
                  duration
                )
              ),
              Empty
            )
          } else {
            (None, this)
          }
        case _ =>
          (None, this)
      }
  }

  final case class Then(first: MockedOperation, second: MockedOperation) extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation) =
      first.matchOperation(operation, input) match {
        case (result, firstRemaining) =>
          (result, Then(firstRemaining, second).normalize)
      }

    def normalize: MockedOperation =
      this match {
        case Then(Empty, second) => second
        case Then(first, Empty)  => first
        case _                   => this
      }
  }

  final case class Or(left: MockedOperation, right: MockedOperation) extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation) =
      left.matchOperation(operation, input) match {
        case (None, leftRemaining) =>
          right.matchOperation(operation, input) match {
            case (result, rightRemaining) =>
              (result, Or(leftRemaining, rightRemaining).normalize)
          }
        case (result, firstRemaining) =>
          (result, Or(firstRemaining, right).normalize)
      }

    def normalize: MockedOperation =
      this match {
        case Or(Empty, right) => right
        case Or(left, Empty)  => left
        case _                => this
      }
  }

  final case class Repeated(mock: MockedOperation, atMost: Int) extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[Match[A]], MockedOperation) =
      mock.matchOperation(operation, input) match {
        case (result, _) =>
          if (atMost > 1)
            (result, Repeated(mock, atMost - 1))
          else
            (result, mock)
      }
  }
}
