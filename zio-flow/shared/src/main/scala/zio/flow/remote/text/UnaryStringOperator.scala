package zio.flow.remote.text

import zio.schema.{DeriveSchema, Schema}

sealed trait UnaryStringOperator

object UnaryStringOperator {
  case object Base64 extends UnaryStringOperator

  implicit val schema: Schema[UnaryStringOperator] = DeriveSchema.gen

  def evaluate(value: String, operator: UnaryStringOperator): String =
    operator match {
      case Base64 =>
        java.util.Base64.getEncoder.encodeToString(value.getBytes)
    }
}
