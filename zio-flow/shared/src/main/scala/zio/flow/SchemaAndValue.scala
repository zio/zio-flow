package zio.flow

import zio.schema.Schema

trait SchemaAndValue[+A] { self =>
  type Subtype <: A

  def schema: Schema[Subtype]

  def value: Subtype

  def toRemote: Remote[A] = Remote.Literal(value, schema)

  def unsafeCoerce[B]: SchemaAndValue[B] = self.asInstanceOf[SchemaAndValue[B]]
}

object SchemaAndValue {
  def apply[A](schema0: Schema[A], value0: A): SchemaAndValue[A] =
    new SchemaAndValue[A] {
      override type Subtype = A

      override def schema: Schema[Subtype] = schema0

      override def value: Subtype = value0
      //TODO : Equals and Hashcode required
    }

  def unapply[A](schemaAndValue: SchemaAndValue[A]): Option[(Schema[schemaAndValue.Subtype], schemaAndValue.Subtype)] =
    Some((schemaAndValue.schema, schemaAndValue.value))
}
