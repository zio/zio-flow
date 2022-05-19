package zio.flow.mock

import zio.flow.Operation
import zio.test.Assertion.anything

import java.net.URI

// TODO: move to a separate published module to support testing user flows

trait MockedOperation {
  def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[A], MockedOperation)
}

object MockedOperation {
  case object Empty extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[A], MockedOperation) =
      (None, Empty)
  }
  final case class Http[R, A](
    urlMatcher: zio.test.Assertion[URI] = anything,
    methodMatcher: zio.test.Assertion[String] = anything,
    headersMatcher: zio.test.Assertion[Map[String, String]] = anything,
    inputMatcher: zio.test.Assertion[R] = anything,
    result: () => A
  ) extends MockedOperation {
    override def matchOperation[R1, A1](operation: Operation[R1, A1], input: R1): (Option[A1], MockedOperation) =
      operation match {
        case Operation.Http(url, method, headers, _, _) =>
          // TODO: check R1 and A1 types too
          val m =
            urlMatcher(url) && methodMatcher(method) && headersMatcher(headers) && inputMatcher(input.asInstanceOf[R])
          if (m.isSuccess) {
            (Some(result().asInstanceOf[A1]), Empty)
          } else {
            (None, this)
          }
        case Operation.SendEmail(server, port) =>
          (None, this)
      }
  }

  final case class Then(first: MockedOperation, second: MockedOperation) extends MockedOperation {
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[A], MockedOperation) =
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
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[A], MockedOperation) =
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
    override def matchOperation[R, A](operation: Operation[R, A], input: R): (Option[A], MockedOperation) =
      mock.matchOperation(operation, input) match {
        case (result, _) =>
          if (atMost > 1)
            (result, Repeated(mock, atMost - 1))
          else
            (result, mock)
      }
  }
}
