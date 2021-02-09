package zio.flow

sealed trait Numeric[+A]
object Numeric {
  implicit object NumericShort  extends Numeric[Short]
  implicit object NumericInt    extends Numeric[Int]
  implicit object NumericLong   extends Numeric[Long]
  implicit object NumericFloat  extends Numeric[Float]
  implicit object NumericDouble extends Numeric[Double]
}
