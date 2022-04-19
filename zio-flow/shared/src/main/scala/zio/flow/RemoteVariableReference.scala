package zio.flow

import zio.schema.{DeriveSchema, Schema}

case class RemoteVariableReference[A](name: RemoteVariableName) {
  def toRemote(implicit schema: Schema[A]): Remote.Variable[A] = Remote.Variable(name, schema)
}
object RemoteVariableReference {
  implicit def schema[A]: Schema[RemoteVariableReference[A]] = DeriveSchema.gen
}
