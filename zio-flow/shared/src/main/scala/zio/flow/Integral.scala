package zio.flow

sealed trait Integral[A] {
  def schema: Schema[A]
}
object Integral extends IntegralImplicits0 {
  implicit object IntegralInt extends Integral[Int] {
    def schema: Schema[Int] = implicitly[Schema[Int]]
  }
}
sealed trait IntegralImplicits0 {
  implicit case object IntegralShort      extends Integral[Short]      {
    def schema: Schema[Short] = implicitly[Schema[Short]]
  }
  implicit case object IntegralLong       extends Integral[Long]       {
    def schema: Schema[Long] = implicitly[Schema[Long]]
  }
  implicit case object IntegralFloat      extends Integral[Float]      {
    def schema: Schema[Float] = implicitly[Schema[Float]]
  }
  implicit case object IntegralDouble     extends Integral[Double]     {
    def schema: Schema[Double] = implicitly[Schema[Double]]
  }
  implicit case object IntegralBigInt     extends Integral[BigInt]     {
    def schema: Schema[BigInt] = implicitly[Schema[BigInt]]
  }
  implicit case object IntegralBigDecimal extends Integral[BigDecimal] {
    def schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]
  }
}
