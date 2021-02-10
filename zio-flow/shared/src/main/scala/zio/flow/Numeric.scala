package zio.flow

sealed trait Numeric[+A]
object Numeric extends NumericImplicits0 {
  implicit object NumericInt extends Numeric[Int]
}
sealed trait NumericImplicits0 {
  implicit object NumericShort  extends Numeric[Short]
  implicit object NumericLong   extends Numeric[Long]
  implicit object NumericFloat  extends Numeric[Float]
  implicit object NumericDouble extends Numeric[Double]
}
