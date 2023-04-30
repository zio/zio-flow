package zio.flow.activities.sendgrid

import zio.flow.Remote
import zio.schema.{DeriveSchema, Schema}

final case class CategoryName(name: String)

object CategoryName {
  implicit val schema: Schema[CategoryName] = Schema[String].transform(CategoryName(_), _.name)

  val derivedSchema = DeriveSchema.gen[CategoryName]
  val (name)        = Remote.makeAccessors[CategoryName](derivedSchema)
}
