package zio.flow.remote

import zio.flow.Remote
import zio.schema._

trait RemoteOpticSpecPersonVersionSpecific {
  import RemoteOpticSpec._

  val derivedPersonSchema = DeriveSchema.gen[Person]

  implicit val schema: Schema[Person] =  derivedPersonSchema
  val (name, age) = Remote.makeAccessors[Person](derivedPersonSchema)
}