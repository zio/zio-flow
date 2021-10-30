package zio.flow.remote

import zio.schema.Schema

sealed trait Numeric[A] {
  def schema: Schema[A]

  def fromLong(l: Long): Remote[A]

  def add(left: A, right: A): A

  def multiply(left: A, right: A): A

  def divide(left: A, right: A): A

  def pow(left: A, right: A): A

  def negate(left: A): A

  def root(left: A, right: A): A

  def log(left: A, right: A): A

  def abs(left: A): A

  def min(left: A, right: A): A

  def max(left: A, right: A): A

  def floor(left: A): A

  def ceil(left: A): A

  def round(left: A): A

}

object Numeric extends NumericImplicits0 {
  implicit object NumericInt extends Numeric[Int] {
    override def fromLong(l: Long): Remote[Int] = Remote(l.toInt)

    def add(left: Int, right: Int): Int = left + right

    override def multiply(left: Int, right: Int): Int = left * right

    override def divide(left: Int, right: Int): Int = left / right

    override def pow(left: Int, right: Int): Int = Math.pow(left.toDouble, right.toDouble).toInt

    override def root(left: Int, right: Int): Int = Math.pow(left.toDouble, 1 / right.toDouble).toInt

    override def log(left: Int, right: Int): Int = (Math.log(left.toDouble) / Math.log(right.toDouble)).toInt

    def schema: Schema[Int] = implicitly[Schema[Int]]

    override def negate(left: Int): Int = -1 * left

    def mod(left: Int, right: Int): Int = left % right

    override def abs(left: Int): Int = Math.abs(left)

    override def min(left: Int, right: Int): Int = Math.min(left, right)

    override def max(left: Int, right: Int): Int = Math.max(left, right)

    override def floor(left: Int): Int = Math.floor(left.toDouble).toInt

    override def ceil(left: Int): Int = Math.ceil(left.toDouble).toInt

    override def round(left: Int): Int = Math.round(left.toFloat)
  }
}

sealed trait NumericImplicits0 {

  implicit case object NumericShort extends Numeric[Short] {
    override def fromLong(l: Long): Remote[Short] = Remote(l.toShort)

    override def add(left: Short, right: Short): Short = (left + right).shortValue()

    override def multiply(left: Short, right: Short): Short = (left * right).shortValue()

    override def divide(left: Short, right: Short): Short = (left / right).shortValue()

    override def pow(left: Short, right: Short): Short = Math.pow(left.toDouble, right.toDouble).toShort

    override def root(left: Short, right: Short): Short = Math.pow(left.toDouble, 1 / right.toDouble).toShort

    override def log(left: Short, right: Short): Short = (Math.log(left.toDouble) / Math.log(right.toDouble)).toShort

    override def negate(left: Short): Short = (-1 * left).toShort

    def schema: Schema[Short] = implicitly[Schema[Short]]

    override def abs(left: Short): Short = Math.abs(left.toDouble).toShort

    override def min(left: Short, right: Short): Short = Math.min(left.toDouble, right.toDouble).toShort

    override def max(left: Short, right: Short): Short = Math.max(left.toDouble, right.toDouble).toShort

    override def floor(left: Short): Short = Math.floor(left.toDouble).toShort

    override def ceil(left: Short): Short = Math.ceil(left.toDouble).toShort

    override def round(left: Short): Short = Math.round(left.toDouble).toShort
  }

  implicit case object NumericLong extends Numeric[Long] {
    override def fromLong(l: Long): Remote[Long] = Remote(l)

    override def add(left: Long, right: Long): Long = left + right

    override def multiply(left: Long, right: Long): Long = left * right

    override def divide(left: Long, right: Long): Long = left / right

    override def pow(left: Long, right: Long): Long = Math.pow(left.toDouble, right.toDouble).toLong

    override def root(left: Long, right: Long): Long = Math.pow(left.toDouble, 1 / right.toDouble).toLong

    override def negate(left: Long): Long = -1 * left

    override def log(left: Long, right: Long): Long = (Math.log(left.toDouble) / Math.log(right.toDouble)).toLong

    def schema: Schema[Long] = implicitly[Schema[Long]]

    override def abs(left: Long): Long = Math.abs(left)

    override def min(left: Long, right: Long): Long = Math.min(left, right)

    override def max(left: Long, right: Long): Long = Math.max(left, right)

    override def floor(left: Long): Long = Math.floor(left.toDouble).toLong

    override def ceil(left: Long): Long = Math.ceil(left.toDouble).toLong

    override def round(left: Long): Long = Math.round(left.toDouble)
  }

  implicit case object NumericBigInt extends Numeric[BigInt] {
    override def fromLong(l: Long): Remote[BigInt] = Remote(BigInt(l))

    override def add(left: BigInt, right: BigInt): BigInt = left + right

    override def multiply(left: BigInt, right: BigInt): BigInt = left * right

    override def divide(left: BigInt, right: BigInt): BigInt = left / right

    override def pow(left: BigInt, right: BigInt): BigInt = BigInt(Math.pow(left.doubleValue, right.doubleValue).toInt)

    override def root(left: BigInt, right: BigInt): BigInt = BigInt(
      Math.pow(left.doubleValue, 1 / right.doubleValue).toInt
    )

    override def negate(left: BigInt): BigInt = -1 * left

    override def log(left: BigInt, right: BigInt): BigInt = BigInt(
      (Math.log(left.doubleValue) / Math.log(right.doubleValue)).toInt
    )

    def schema: Schema[BigInt] = implicitly[Schema[BigInt]]

    override def abs(left: BigInt): BigInt = Math.abs(left.toInt)

    override def min(left: BigInt, right: BigInt): BigInt = Math.min(left.toInt, right.toInt)

    override def max(left: BigInt, right: BigInt): BigInt = Math.max(left.toInt, right.toInt)

    override def floor(left: BigInt): BigInt = Math.floor(left.doubleValue).toInt

    override def ceil(left: BigInt): BigInt = Math.ceil(left.doubleValue).toInt

    override def round(left: BigInt): BigInt = Math.round(left.doubleValue)
  }

  implicit case object NumericFloat extends Numeric[Float] {
    override def fromLong(l: Long): Remote[Float] = Remote(l.toFloat)

    override def add(left: Float, right: Float): Float = left + right

    override def multiply(left: Float, right: Float): Float = left * right

    override def divide(left: Float, right: Float): Float = left / right

    override def pow(left: Float, right: Float): Float = Math.pow(left.toDouble, right.toDouble).toFloat

    override def root(left: Float, right: Float): Float = Math.pow(left.toDouble, 1 / right.toDouble).toFloat

    override def negate(left: Float): Float = -1 * left

    override def log(left: Float, right: Float): Float = (Math.log(left.toDouble) / Math.log(right.toDouble)).toFloat

    def schema: Schema[Float] = implicitly[Schema[Float]]

    override def abs(left: Float): Float = Math.abs(left)

    override def min(left: Float, right: Float): Float = Math.min(left, right)

    override def max(left: Float, right: Float): Float = Math.max(left, right)

    override def floor(left: Float): Float = Math.floor(left.toDouble).toFloat

    override def ceil(left: Float): Float = Math.ceil(left.toDouble).toFloat

    override def round(left: Float): Float = Math.round(left).toFloat
  }

  implicit case object NumericDouble extends Numeric[Double] {
    override def fromLong(l: Long): Remote[Double] = Remote(l.toDouble)

    override def add(left: Double, right: Double): Double = left + right

    override def multiply(left: Double, right: Double): Double = left * right

    override def divide(left: Double, right: Double): Double = left / right

    override def pow(left: Double, right: Double): Double = Math.pow(left, right)

    override def root(left: Double, right: Double): Double = Math.pow(left, 1 / right)

    override def negate(left: Double): Double = -1 * left

    override def log(left: Double, right: Double): Double = Math.log(left) / Math.log(right)

    def schema: Schema[Double] = implicitly[Schema[Double]]

    override def abs(left: Double): Double = Math.abs(left)

    override def min(left: Double, right: Double): Double = Math.min(left, right)

    override def max(left: Double, right: Double): Double = Math.max(left, right)

    override def floor(left: Double): Double = Math.floor(left)

    override def ceil(left: Double): Double = Math.ceil(left)

    override def round(left: Double): Double = Math.round(left).toDouble
  }

  implicit case object NumericBigDecimal extends Numeric[BigDecimal] {
    override def fromLong(l: Long): Remote[BigDecimal] = Remote(BigDecimal(l))

    override def add(left: BigDecimal, right: BigDecimal): BigDecimal = left + right

    override def multiply(left: BigDecimal, right: BigDecimal): BigDecimal = left * right

    override def divide(left: BigDecimal, right: BigDecimal): BigDecimal = left / right

    override def pow(left: BigDecimal, right: BigDecimal): BigDecimal = BigDecimal(
      Math.pow(left.doubleValue, right.doubleValue)
    )

    override def root(left: BigDecimal, right: BigDecimal): BigDecimal =
      Math.pow(left.doubleValue, 1 / right.doubleValue)

    override def log(left: BigDecimal, right: BigDecimal): BigDecimal =
      Math.log(left.doubleValue) / Math.log(right.doubleValue)

    override def negate(left: BigDecimal): BigDecimal = -1 * left

    def schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]

    override def abs(left: BigDecimal): BigDecimal = Math.abs(left.doubleValue)

    override def min(left: BigDecimal, right: BigDecimal): BigDecimal = Math.min(left.doubleValue, right.doubleValue)

    override def max(left: BigDecimal, right: BigDecimal): BigDecimal = Math.max(left.doubleValue, right.doubleValue)

    override def floor(left: BigDecimal): BigDecimal = Math.floor(left.doubleValue)

    override def ceil(left: BigDecimal): BigDecimal = Math.ceil(left.doubleValue)

    override def round(left: BigDecimal): BigDecimal = Math.round(left.doubleValue)
  }
}

sealed trait Fractional[A] extends Numeric[A] {
  def fromDouble(const: Double): A

  def schema: Schema[A]

  def sin(a: A): A

  def inverseSin(a: A): A

  def inverseTan(a: A): A

}

object Fractional {

  implicit case object FractionalFloat extends Fractional[Float] {
    def fromDouble(const: Double): Float = const.toFloat

    override def fromLong(l: Long): Remote[Float] = Remote(l.toFloat)

    override def add(left: Float, right: Float): Float = left + right

    override def multiply(left: Float, right: Float): Float = left * right

    override def divide(left: Float, right: Float): Float = left / right

    override def pow(left: Float, right: Float): Float = Math.pow(left.toDouble, right.toDouble).toFloat

    override def root(left: Float, right: Float): Float = Math.pow(left.toDouble, 1 / right.toDouble).toFloat

    override def negate(left: Float): Float = -1 * left

    override def log(left: Float, right: Float): Float = (Math.log(left.toDouble) / Math.log(right.toDouble)).toFloat

    override def sin(a: Float): Float = Math.sin(a.toDouble).toFloat

    def schema: Schema[Float] = implicitly[Schema[Float]]

    override def abs(left: Float): Float = Math.abs(left)

    override def inverseSin(a: Float): Float = Math.asin(a.toDouble).toFloat

    override def inverseTan(a: Float): Float = Math.atan(a.toDouble).toFloat


    override def min(left: Float, right: Float): Float = Math.min(left, right)

    override def max(left: Float, right: Float): Float = Math.max(left, right)

    override def floor(left: Float): Float = Math.floor(left.toDouble).toFloat

    override def ceil(left: Float): Float = Math.ceil(left.toDouble).toFloat

    override def round(left: Float): Float = Math.round(left).toFloat

    override def inverseSin(a: Float): Float = Math.asin(a.toDouble).toFloat

    override def inverseTan(a: Float): Float = Math.atan(a.toDouble).toFloat

  }

  implicit case object FractionalDouble extends Fractional[Double] {

    def fromDouble(const: Double): Double = const.toDouble

    override def fromLong(l: Long): Remote[Double] = Remote(l.toDouble)

    override def add(left: Double, right: Double): Double = left + right

    override def multiply(left: Double, right: Double): Double = left * right

    override def divide(left: Double, right: Double): Double = left / right

    override def pow(left: Double, right: Double): Double = Math.pow(left, right)

    override def root(left: Double, right: Double): Double = Math.pow(left, 1 / right)

    override def log(left: Double, right: Double): Double = Math.log(left) / Math.log(right)

    override def negate(left: Double): Double = -1 * left

    override def sin(a: Double): Double = Math.sin(a)

    def schema: Schema[Double] = implicitly[Schema[Double]]

    override def abs(left: Double): Double = Math.abs(left)

    override def inverseSin(a: Double): Double = Math.asin(a)

    override def inverseTan(a: Double): Double = Math.atan(a)


    override def min(left: Double, right: Double): Double = Math.min(left, right)

    override def max(left: Double, right: Double): Double = Math.max(left, right)

    override def floor(left: Double): Double = Math.floor(left)

    override def ceil(left: Double): Double = Math.ceil(left)

    override def round(left: Double): Double = Math.round(left).toDouble

    override def inverseSin(a: Double): Double = Math.asin(a)

    override def inverseTan(a: Double): Double = Math.atan(a)

  }

  implicit case object FractionalBigDecimal extends Fractional[BigDecimal] {

    def fromDouble(const: Double): BigDecimal = BigDecimal(const)

    override def fromLong(l: Long): Remote[BigDecimal] = Remote(BigDecimal(l))

    override def add(left: BigDecimal, right: BigDecimal): BigDecimal = left + right

    override def multiply(left: BigDecimal, right: BigDecimal): BigDecimal = left * right

    override def divide(left: BigDecimal, right: BigDecimal): BigDecimal = left / right

    override def pow(left: BigDecimal, right: BigDecimal): BigDecimal = Math.pow(left.doubleValue, right.doubleValue)

    override def root(left: BigDecimal, right: BigDecimal): BigDecimal =
      Math.pow(left.doubleValue, 1 / right.doubleValue)

    override def negate(left: BigDecimal): BigDecimal = -1 * left

    override def log(left: BigDecimal, right: BigDecimal): BigDecimal =
      Math.log(left.doubleValue) / Math.log(right.doubleValue)

    override def sin(a: BigDecimal): BigDecimal = Math.sin(a.doubleValue)

    def schema: Schema[BigDecimal] = implicitly[Schema[BigDecimal]]

    override def inverseSin(a: BigDecimal): BigDecimal = Math.asin(a.doubleValue)

    override def inverseTan(a: BigDecimal): BigDecimal =
      Math.atan(a.doubleValue)


    override def abs(left: BigDecimal): BigDecimal = Math.abs(left.doubleValue)

    override def min(left: BigDecimal, right: BigDecimal): BigDecimal = Math.min(left.doubleValue, right.doubleValue)

    override def max(left: BigDecimal, right: BigDecimal): BigDecimal = Math.max(left.doubleValue, right.doubleValue)

    override def floor(left: BigDecimal): BigDecimal = Math.floor(left.doubleValue)

    override def ceil(left: BigDecimal): BigDecimal = Math.ceil(left.doubleValue)

    override def round(left: BigDecimal): BigDecimal = Math.round(left.doubleValue)
  }
}
