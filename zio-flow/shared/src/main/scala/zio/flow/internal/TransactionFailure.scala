package zio.flow.internal

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

  /** Wraps a dynamic value E with TransactionFailure.Fail(value) */
  def wrapDynamic[E](value: DynamicValue): DynamicValue =
    DynamicValue.Enumeration(
      "Fail" -> value
    )

  /**
   * Unwraps a dynamic value of type TransactionFailure.Fail or returns None if
   * it was TransactionFailure.Retry
   */
  def unwrapDynamic(dynamicValue: DynamicValue): Option[DynamicValue] =
    dynamicValue match {
      case DynamicValue.Enumeration((name, value)) =>
        name match {
          case "Retry" => None
          case "Fail" =>
            Some(value)
          case _ =>
            throw new IllegalArgumentException(s"TransactionFailure.unwrapDynamic called with an unexpected schema")
        }
      case _ =>
        throw new IllegalArgumentException(s"TransactionFailure.unwrapDynamic called with an unexpected dynamic value")
    }
}
