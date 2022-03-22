package zio.flow

import zio.schema.{DynamicValue, Schema}

trait SchemaAndValue[+A] { self =>
  type Subtype <: A

  def schema: Schema[Subtype]

  def value: DynamicValue

  def toRemote: Remote[A] = Remote.Literal(value, schema)

  def toTyped: Either[String, Subtype] = value.toTypedValue(schema)

  def unsafeCoerce[B]: SchemaAndValue[B] = self.asInstanceOf[SchemaAndValue[B]]
}

object SchemaAndValue {
  def apply[A](schema0: Schema[A], value0: DynamicValue): SchemaAndValue[A] =
    new SchemaAndValue[A] {
      override type Subtype = A

      override def schema: Schema[Subtype] = schema0

      override def value: DynamicValue = value0

      override def equals(obj: Any): Boolean =
        obj match {
          case other: SchemaAndValue[_] =>
            value == other.value && Schema.structureEquality.equal(schema, other.schema)
          case _ => false
        }

      override def hashCode(): Int =
        value.hashCode() ^ schema.ast.hashCode()
    }

  def apply[A](schema0: SchemaOrNothing.Aux[A], value0: DynamicValue): SchemaAndValue[A] =
    apply(schema0.schema, value0)

  // TODO: make a version of this that implicilty captures the schema and use it everywhere
  def fromSchemaAndValue[A](schema: Schema[A], value: A): SchemaAndValue[A] =
    SchemaAndValue(schema, DynamicValue.fromSchemaAndValue(schema, value))

  def unapply[A](schemaAndValue: SchemaAndValue[A]): Option[(Schema[schemaAndValue.Subtype], DynamicValue)] =
    Some((schemaAndValue.schema, schemaAndValue.value))
}
