package zio.flow.internal

import zio.flow.SchemaAndValue
import zio.schema.{DynamicValue, Schema}

sealed trait TransactionFailure[+E]
object TransactionFailure {
  case object Retry                  extends TransactionFailure[Nothing]
  final case class Fail[E](value: E) extends TransactionFailure[E]

  implicit def schema[E: Schema]: Schema[TransactionFailure[E]] =
    Schema.Enum2(
      Schema.Case[Retry.type, TransactionFailure[E]](
        "Retry",
        Schema.singleton(Retry),
        _.asInstanceOf[Retry.type]
      ),
      Schema.Case[TransactionFailure.Fail[E], TransactionFailure[E]](
        "Fail",
        implicitly[Schema[E]].transform(Fail(_), _.value),
        _.asInstanceOf[Fail[E]]
      )
    )

  def wrapDynamic[E](value: SchemaAndValue[E]): SchemaAndValue[TransactionFailure[E]] = {
    val result = SchemaAndValue(
      schema(value.schema),
      DynamicValue.Enumeration(
        "Fail" -> value.value
      )
    )
    result
  }

  def unwrapDynamic(schemaAndValue: SchemaAndValue[Any]): Option[SchemaAndValue[Any]] =
    schemaAndValue.value match {
      case DynamicValue.Enumeration((name, value)) =>
        name match {
          case "Retry" => None
          case "Fail" =>
            val errorSchema = schemaAndValue.schema match {
              case Schema.Enum2(_, failSchema, _) =>
                failSchema.codec
              case _ =>
                throw new IllegalArgumentException(s"TransactionFailure.unwrapDynamic called with an unexpected schema")
            }
            Some(SchemaAndValue(errorSchema, value))
          case _ =>
            throw new IllegalArgumentException(s"TransactionFailure.unwrapDynamic called with an unexpected schema")
        }
      case _ =>
        throw new IllegalArgumentException(s"TransactionFailure.unwrapDynamic called with an unexpected dynamic value")
    }
}
