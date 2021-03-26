package zio.flow

import zio.test.Assertion.equalTo
import zio.test.{DefaultRunnableSpec, ZSpec, _}

object NumericSpec extends DefaultRunnableSpec {

  val suite1 = suite("NumericAdditionSpec")(test("Test addition of Remote Integer.") {
    val fInt = Remote(1)
    val sInt = Remote(2)
    assert((fInt + sInt).eval)(equalTo(Right(3)))
  },
    test("Test addition of Remote Long"){
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong + sLong).eval)(equalTo(Right(3L)))
    },
    test("Test addition of Remote Short"){
      val fLong = Remote(1.toShort)
      val sLong = Remote(2.toShort)
      assert((fLong + sLong).eval)(equalTo(Right(3.toShort)))
    })

  val suite2 = suite("NumericSubtractionSpec")(test("Test subtraction of Remote Numerics.") {
    val fInt = Remote(1)
    val sInt = Remote(2)
    assert((fInt - sInt).eval)(equalTo(Right(-1)))
  },
    test("Test subtraction of Remote Long"){
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong - sLong).eval)(equalTo(Right(-1L)))
    },
    test("Test subtraction of Remote Short"){
      val fLong = Remote(1.toShort)
      val sLong = Remote(2.toShort)
      assert((fLong - sLong).eval)(equalTo(Right((-1).toShort)))
    })

  val suite3 = suite("NumericMultiplicationSpec")(test("Test subtraction of Remote Numerics.") {
    val fInt: Remote[Int] = Remote(1)
    val sInt = Remote(2)
    assert((fInt * sInt).eval)(equalTo(Right(2)))
  },
    test("Test multiplication of Remote Long"){
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong * sLong).eval)(equalTo(Right(2L)))
    },
    test("Test multiplication of Remote Short"){
      val fLong = Remote(1.toShort)
      val sLong = Remote(2.toShort)
      assert((fLong * sLong).eval)(equalTo(Right(2.toShort)))
    })

  val suite4 = suite("NumericDivisionSpec")(test("Test subtraction of Remote Numerics.") {
    val fInt: Remote[Int] = Remote(1)
    val sInt: Remote[Int] = Remote(2)
    assert((fInt / sInt).eval)(equalTo(Right(0)))
  },
    test("Test division of Remote Long"){
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong / sLong).eval)(equalTo(Right(0L)))
    },
    test("Test division of Remote Short"){
      val fLong = Remote(1.toShort)
      val sLong = Remote(2.toShort)
      assert((fLong / sLong).eval)(equalTo(Right(0.toShort)))
    })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("NumericSpec")(suite1, suite2, suite3, suite4)
}
