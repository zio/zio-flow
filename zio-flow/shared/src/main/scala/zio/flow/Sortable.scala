package zio.flow

sealed trait Sortable[A]
object Sortable {
  implicit object SortableInt extends Sortable[Int]
}
