package zio.flow.remote

import zio.flow.Remote
import zio.schema._

trait RemoteOpticSpecColorVersionSpecific {
  import RemoteOpticSpec._

  implicit val schema: Schema[Color] = DeriveSchema.gen[Color]
  implicit val blueSchema: Schema[Blue.type] = DeriveSchema.gen[Blue.type]

  val (blue, green, red) = Remote.makeAccessors[Color]
}