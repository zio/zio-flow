package zio.flow.runtime.internal

import zio.flow.{FlowId, RemoteVariableName, TransactionId}
import zio.test.{Gen, Sized}

object Generators {
  lazy val genRemoteVariableName: Gen[Sized, RemoteVariableName] =
    Gen.string1(Gen.alphaNumericChar).map(RemoteVariableName.unsafeMake)

  lazy val genFlowId: Gen[Sized, FlowId] =
    Gen.alphaNumericStringBounded(1, 16).map(FlowId.unsafeMake)

  lazy val genTransactionId: Gen[Sized, TransactionId] =
    Gen.alphaNumericStringBounded(1, 16).map(TransactionId.unsafeMake)

  lazy val genScope: Gen[Sized, RemoteVariableScope] =
    Gen.suspend {
      Gen.oneOf(
        genFlowId.map(RemoteVariableScope.TopLevel),
        (genFlowId <*> genScope).map { case (id, scope) => RemoteVariableScope.Fiber(id, scope) },
        (genTransactionId <*> genScope).map { case (id, scope) => RemoteVariableScope.Transactional(scope, id) }
      )
    }

  lazy val genScopedRemoteVariableName: Gen[Sized, ScopedRemoteVariableName] =
    (genRemoteVariableName <*> genScope).map { case (name, scope) => ScopedRemoteVariableName(name, scope) }

}
