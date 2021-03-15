package zio.flow

sealed trait Sortable[A] {
  def lessThan(left: A, right: A) : Boolean = ???
}
object Sortable {
  implicit case object SortableInt    extends Sortable[Int]
  implicit case object SortableLong   extends Sortable[Long]
  implicit case object SortableFloat  extends Sortable[Float]
  implicit case object SortableDouble extends Sortable[Double]
}
