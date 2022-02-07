package zio.flow

import zio.schema.Schema

package object serialization {
  // TODO: this does not belong here
  implicit val nothingSchema: Schema[Nothing] = Schema.fail("Nothing")
}
