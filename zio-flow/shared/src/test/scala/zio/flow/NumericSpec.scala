package zio.flow

import zio.test.Assertion.equalTo
import zio.test.{DefaultRunnableSpec, ZSpec, _}

object NumericSpec extends DefaultRunnableSpec {

  val suite1 = suite("NumericAdditionSpec")(test("Test addition of Remote Integer.") {
    val fInt = Remote(1)
    val sInt = Remote(2)
    assert((fInt + sInt).eval)(equalTo(Right(3)))
  },
    test("Test addition of Remote Long") {
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong + sLong).eval)(equalTo(Right(3L)))
    },
    test("Test addition of Remote Short") {
      val fShort = Remote(1.toShort)
      val sShort = Remote(2.toShort)
      assert((fShort + sShort).eval)(equalTo(Right(3.toShort)))
    },
    test("Test addition of Remote Float") {
      val fFloat = Remote(1.555f)
      val sFloat = Remote(2.333f)
      assert((fFloat + sFloat).eval)(equalTo(Right(3.888f)))
    },
    test("Test addition of Remote Double") {
      val fFloat = Remote(1.555657)
      val sFloat = Remote(2.333665)
      assert((fFloat + sFloat).eval)(equalTo(Right(3.889322)))
    })

  val suite2 = suite("NumericSubtractionSpec")(test("Test subtraction of Remote Numerics.") {
    val fInt = Remote(1)
    val sInt = Remote(2)
    assert((fInt - sInt).eval)(equalTo(Right(-1)))
  },
    test("Test subtraction of Remote Long") {
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong - sLong).eval)(equalTo(Right(-1L)))
    },
    test("Test subtraction of Remote Short") {
      val fShort = Remote(1.toShort)
      val sShort = Remote(2.toShort)
      assert((fShort - sShort).eval)(equalTo(Right((-1).toShort)))
    },
    test("Test subtraction of Remote Float") {
      val fFloat = Remote(1.555f)
      val sFloat = Remote(2.333f)
      assert((fFloat - sFloat).eval)(equalTo(Right(-0.778f)))
    })

  val suite3 = suite("NumericMultiplicationSpec")(test("Test subtraction of Remote Numerics.") {
    val fInt: Remote[Int] = Remote(1)
    val sInt = Remote(2)
    assert((fInt * sInt).eval)(equalTo(Right(2)))
  },
    test("Test multiplication of Remote Long") {
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong * sLong).eval)(equalTo(Right(2L)))
    },
    test("Test multiplication of Remote Short") {
      val fShort = Remote(1.toShort)
      val sShort = Remote(2.toShort)
      assert((fShort * sShort).eval)(equalTo(Right(2.toShort)))
    },
    test("Test multiplication of Remote Float") {
      val fFloat = Remote(1.555f)
      val sFloat = Remote(2.333f)
      assert((fFloat * sFloat).eval)(equalTo(Right(3.6278148f)))
    })

  val suite4 = suite("NumericDivisionSpec")(test("Test subtraction of Remote Numerics.") {
    val fInt: Remote[Int] = Remote(1)
    val sInt: Remote[Int] = Remote(2)
    assert((fInt / sInt).eval)(equalTo(Right(0)))
  },
    test("Test division of Remote Long") {
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong / sLong).eval)(equalTo(Right(0L)))
    },
    test("Test division of Remote Short") {
      val fShort = Remote(1.toShort)
      val sShort = Remote(2.toShort)
      assert((fShort / sShort).eval)(equalTo(Right(0.toShort)))
    },
    test("Test division of Remote Float") {
      val fFloat = Remote(1.555f)
      val sFloat = Remote(2.333f)
      assert((fFloat / sFloat).eval)(equalTo(Right(0.66652375f)))
    })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("NumericSpec")(suite1, suite2, suite3, suite4)
}
