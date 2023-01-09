package zio.flow.remote

import zio.flow.Remote
import zio.schema._

trait RemoteOpticSpecPersonVersionSpecific {
  import RemoteOpticSpec._

  implicit val schema: Schema[Person] = DeriveSchema.gen[Person]
  val (name, age) = Remote.makeAccessors[Person]
}