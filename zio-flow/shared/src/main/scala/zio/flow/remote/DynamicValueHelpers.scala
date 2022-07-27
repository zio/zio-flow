package zio.flow.remote

import zio.schema.{DynamicValue, Schema}

object DynamicValueHelpers {

  def of[A: Schema](value: A): DynamicValue =
    DynamicValue.fromSchemaAndValue(Schema[A], value)

  def tuple(values: DynamicValue*): DynamicValue =
    values.toList match {
      case (left :: last :: Nil)   => DynamicValue.Tuple(left, last)
      case (left :: right :: rest) => tuple(DynamicValue.Tuple(left, right) :: rest: _*)
      case _                       => throw new IllegalArgumentException(s"DynamicValueHelpers.tuple requires at least two parameters")
    }
}
