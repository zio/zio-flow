package zio.flow.runtime

import zio.constraintless.TypeList.{::, End}
import zio.flow.runtime.internal.PersistentExecutor.FlowResult
import zio.flow.runtime.internal.{PersistentExecutor, ScopedRemoteVariableName}
import zio.schema.DynamicValue
import zio.schema.codec.BinaryCodecs

package object serialization {
  type ExecutorBinaryCodecs =
    BinaryCodecs[
      PersistentExecutor.State[Any, Any] ::
        ScopedRemoteVariableName ::
        FlowResult ::
        Either[Either[ExecutorError, DynamicValue], FlowResult] ::
        DynamicValue ::
        PersistentExecutor.StateChange ::
        End
    ]

  lazy val json: ExecutorBinaryCodecs = {
    import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec
    BinaryCodecs.make
  }

  lazy val protobuf: ExecutorBinaryCodecs = {
    import zio.schema.codec.ProtobufCodec.protobufCodec
    BinaryCodecs.make
  }
}
