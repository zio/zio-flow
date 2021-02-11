package zio.flow
import zio.schema._

sealed trait Fractional[A] {
  def schema: Schema[A]
}
object Fractional          {
  import SchemaImplicit._
  implicit object FractionalFloat      extends Fractional[Float]      {
    def schema: Schema[Float] = implicitly[Schema[Float]]
  }
  implicit object FractionalDouble     extends Fractional[Double]     {
    def schema: Schema[Double] = implicitly[Schema[Double]]
  }
  implicit object FractionalBigDecimal extends Fractional[BigDecimal] {
    def schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]
  }
}
