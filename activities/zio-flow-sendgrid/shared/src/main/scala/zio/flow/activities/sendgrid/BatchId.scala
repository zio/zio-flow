package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class BatchId(value: String) extends AnyVal

object BatchId {
  implicit val schema: Schema[BatchId] = Schema[String].transform(BatchId(_), _.value)

  val derivedSchema = DeriveSchema.gen[BatchId]
  val (value)       = Remote.makeAccessors[BatchId](derivedSchema)
}
