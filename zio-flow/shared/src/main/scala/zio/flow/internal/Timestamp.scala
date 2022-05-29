package zio.flow.internal

import zio.schema.Schema

case class Timestamp(value: Long) {
  def <(other: Timestamp): Boolean     = value < other.value
  def <=(other: Timestamp): Boolean    = value <= other.value
  def >(other: Timestamp): Boolean     = value > other.value
  def >=(other: Timestamp): Boolean    = value >= other.value
  def max(other: Timestamp): Timestamp = Timestamp(Math.max(value, other.value))

  def next: Timestamp = Timestamp(value + 1)
}

object Timestamp {
  implicit val schema: Schema[Timestamp] = Schema[Long].transform(
    Timestamp(_),
    _.value
  )
}
