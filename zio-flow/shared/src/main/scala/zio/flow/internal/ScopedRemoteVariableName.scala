package zio.flow.internal

import zio.flow.RemoteVariableName
import zio.schema.{DeriveSchema, Schema}

final case class ScopedRemoteVariableName(name: RemoteVariableName, scope: RemoteVariableScope)

object ScopedRemoteVariableName {
  implicit val schema: Schema[ScopedRemoteVariableName] = DeriveSchema.gen
}
