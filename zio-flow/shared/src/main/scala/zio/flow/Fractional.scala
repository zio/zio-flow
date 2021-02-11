package zio.flow

sealed trait Fractional[A] {
  def schema: Schema[A]
}
object Fractional          {
  implicit case object FractionalFloat      extends Fractional[Float]      {
    def schema: Schema[Float] = implicitly[Schema[Float]]
  }
  implicit case object FractionalDouble     extends Fractional[Double]     {
    def schema: Schema[Double] = implicitly[Schema[Double]]
  }
  implicit case object FractionalBigDecimal extends Fractional[BigDecimal] {
    def schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]
  }
}
