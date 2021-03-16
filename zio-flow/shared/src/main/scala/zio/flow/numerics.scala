package zio.flow

sealed trait Numeric[A] {
  def schema: Schema[A]

  def fromLong(l: Long): Remote[A]
  def add(left: A, right: A): A      = ???
  def multiply(left: A, right: A): A = ???
  def divide(left: A, right: A): A   = ???
  def pow(left: A, right: A): A      = ???
  def negate(left: A): A             = ???
  def root(left: A, right: A): A     = ???
  def log(left: A, right: A): A      = ???
}

object Numeric extends NumericImplicits0 {

  implicit object NumericlInt extends Numeric[Int] {
    override def fromLong(l: Long): Remote[Int] = Remote(l.toInt)
    def schema: Schema[Int]                     = implicitly[Schema[Int]]
  }
}
sealed trait NumericImplicits0 {

  implicit case object NumericShort extends Numeric[Short] {
    override def fromLong(l: Long): Remote[Short] = Remote(l.toShort)
    def schema: Schema[Short]                     = implicitly[Schema[Short]]
  }

  implicit case object NumericLong extends Numeric[Long] {
    override def fromLong(l: Long): Remote[Long] = Remote(l)
    def schema: Schema[Long]                     = implicitly[Schema[Long]]
  }

  implicit case object NumericBigInt extends Numeric[BigInt] {
    override def fromLong(l: Long): Remote[BigInt] = Remote(BigInt(l))
    def schema: Schema[BigInt]                     = implicitly[Schema[BigInt]]
  }

  implicit case object NumericFloat extends Numeric[Float] {
    override def fromLong(l: Long): Remote[Float] = Remote(l.toFloat)
    def schema: Schema[Float]                     = implicitly[Schema[Float]]
  }

  implicit case object NumericDouble extends Numeric[Double] {
    override def fromLong(l: Long): Remote[Double] = Remote(l.toDouble)
    def schema: Schema[Double]                     = implicitly[Schema[Double]]
  }

  implicit case object NumericBigDecimal extends Numeric[BigDecimal] {
    override def fromLong(l: Long): Remote[BigDecimal] = Remote(BigDecimal(l))
    def schema: Schema[BigDecimal]                     = implicitly[Schema[BigDecimal]]
  }
}
sealed trait Fractional[A] extends Numeric[A] {
  def fromDouble(const: Double): A

  def schema: Schema[A]

  def sin(a: A): A = ???

  def inverseSin(a: A): A = ???
}

object Fractional {

  implicit case object FractionalFloat extends Fractional[Float] {
    def fromDouble(const: Double): Float = const.toFloat

    override def fromLong(l: Long): Remote[Float] = Remote(l.toFloat)

    def schema: Schema[Float] = implicitly[Schema[Float]]
  }

  implicit case object FractionalDouble extends Fractional[Double] {
    def fromDouble(const: Double): Double = const.toDouble
    override def fromLong(l: Long): Remote[Double] = Remote(l.toDouble)

    def schema: Schema[Double] = implicitly[Schema[Double]]
  }

  implicit case object FractionalBigDecimal extends Fractional[BigDecimal] {
    def fromDouble(const: Double): BigDecimal = BigDecimal(const)
    override def fromLong(l: Long): Remote[BigDecimal] = Remote(BigDecimal(l))
    def schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]
  }
}
