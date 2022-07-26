package zio.flow

import zio.schema.{DeriveSchema, Schema}

case class RemoteVariableReference[A](name: RemoteVariableName) {
  def toRemote: Remote.Variable[A] = Remote.Variable(name)
}
object RemoteVariableReference {
  implicit def schema[A]: Schema[RemoteVariableReference[A]] = DeriveSchema.gen
}
