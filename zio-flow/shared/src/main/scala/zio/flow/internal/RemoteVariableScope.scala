package zio.flow.internal

import zio.flow.{FlowId, TransactionId}

final case class RemoteVariableScope(flowId: FlowId, transactionId: Option[TransactionId])
