package zio.flow

import zio.schema.{DynamicValue, Schema}

trait SchemaAndValue[+A] { self =>
  type Subtype <: A

  def schema: Schema[Subtype]

  def value: DynamicValue

  def toRemote: Remote[A] = Remote.Literal(value, schema)

  def toTyped: Either[String, Subtype] = value.toTypedValue(schema)

  def unsafeCoerce[B]: SchemaAndValue[B] = self.asInstanceOf[SchemaAndValue[B]]

  override def toString: String = s"$value [$schema]"
}

object SchemaAndValue {
  def apply[A](schema0: Schema[A], value0: DynamicValue): SchemaAndValue[A] = {
    assert(value0.toTypedValue(schema0).isRight)

    new SchemaAndValue[A] {
      override type Subtype = A

      override final val schema: Schema[Subtype] = schema0

      override final val value: DynamicValue = value0

      override final def equals(obj: Any): Boolean =
        obj match {
          case other: SchemaAndValue[_] =>
            value == other.value && Schema.structureEquality.equal(schema, other.schema)
          case _ => false
        }

      override final def hashCode(): Int =
        value.hashCode() ^ schema.ast.hashCode()
    }
  }

  def of[A: Schema](value: A): SchemaAndValue[A] =
    fromSchemaAndValue(Schema[A], value)

  def fromSchemaAndValue[A](schema: Schema[A], value: A): SchemaAndValue[A] =
    SchemaAndValue(schema, DynamicValue.fromSchemaAndValue(schema, value))

  def unapply[A](schemaAndValue: SchemaAndValue[A]): Option[(Schema[schemaAndValue.Subtype], DynamicValue)] =
    Some((schemaAndValue.schema, schemaAndValue.value))
}
