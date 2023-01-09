package zio.flow.remote

import zio.flow.Remote
import zio.schema._

trait RemoteOpticSpecColorVersionSpecific {
  import RemoteOpticSpec._

  val derivedColorSchema = DeriveSchema.gen[Color]
  val derivedBlueSchema = DeriveSchema.gen[Blue.type]

  implicit val schema: Schema[Color] = derivedColorSchema
  implicit val blueSchema: Schema[Blue.type] = derivedBlueSchema

  val (blue, green, red) = Remote.makeAccessors[Color](derivedColorSchema)
}