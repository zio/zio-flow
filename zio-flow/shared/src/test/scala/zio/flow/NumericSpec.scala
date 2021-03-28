package zio.flow

import zio.flow.Remote.apply
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
      val fDouble = Remote(1.555657)
      val sDouble = Remote(2.333665)
      assert((fDouble + sDouble).eval)(equalTo(Right(3.889322)))
    },
    test("Test addition of BigInt"){
      val fBigInt = BigInt("111111111111111")
      val sBigInt = BigInt("222222222222222")
      assert((fBigInt + sBigInt).eval)(equalTo(Right(BigInt("333333333333333"))))
    },
    test("Test addition of BigDecimal"){
      val fBigDecimal = BigDecimal("11111.1111111111")
      val sBigDecimal = BigDecimal("22222.2222222222")
      assert((fBigDecimal + sBigDecimal).eval)(equalTo(Right(BigDecimal("33333.3333333333"))))
    }
  )

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
    },
    test("Test subtraction of Remote Double") {
      val fDouble = Remote(1.555657)
      val sDouble = Remote(2.333665)
      assert((fDouble - sDouble).eval)(equalTo(Right(-0.7780079999999998)))
    },
    test("Test subtraction of BigInt"){
      val fBigInt = BigInt("111111111111111")
      val sBigInt = BigInt("222222222222222")
      assert((fBigInt - sBigInt).eval)(equalTo(Right(BigInt("-111111111111111"))))
    },
    test("Test subtraction of BigDecimal"){
      val fBigDecimal = BigDecimal("11111.1111111111")
      val sBigDecimal = BigDecimal("22222.2222222222")
      assert((fBigDecimal - sBigDecimal).eval)(equalTo(Right(BigDecimal("-11111.1111111111"))))
    })

  val suite3 = suite("NumericMultiplicationSpec")(test("Test multiplication of Remote Numerics.") {
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
    },
    test("Test multiplication of Remote Double") {
      val fDouble = Remote(1.555657)
      val sDouble = Remote(2.333665)
      assert((fDouble * sDouble).eval)(equalTo(Right(3.630382292905)))
    },
    test("Test multiplication of BigInt"){
      val fBigInt = BigInt("111111111111111")
      val sBigInt = BigInt("222222222222222")
      assert((fBigInt * sBigInt).eval)(equalTo(Right(BigInt("24691358024691308641975308642"))))
    },
    test("Test subtraction of BigDecimal"){
      val fBigDecimal = BigDecimal("11111.1111111111")
      val sBigDecimal = BigDecimal("22222.2222222222")
      assert((fBigDecimal * sBigDecimal).eval)(equalTo(Right(BigDecimal("246913580.24691308641975308642"))))
    })

  val suite4 = suite("NumericDivisionSpec")(test("Test division of Remote Numerics.") {
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
    },
    test("Test division of Remote Double") {
      val fDouble = Remote(1.555657)
      val sDouble = Remote(2.333665)
      assert((fDouble / sDouble).eval)(equalTo(Right(0.6666153882412429)))
    },
    test("Test division of BigInt"){
      val fBigInt = BigInt("111111111111111")
      val sBigInt = BigInt("222222222222222")
      assert((fBigInt / sBigInt).eval)(equalTo(Right(BigInt("0"))))
    },
    test("Test subtraction of BigDecimal"){
      val fBigDecimal = BigDecimal("11111.1111111111")
      val sBigDecimal = BigDecimal("22222.2222222222")
      assert((fBigDecimal / sBigDecimal).eval)(equalTo(Right(BigDecimal("0.5"))))
    })

  val suite5 = suite("NumericLogSpec")(test("Test log of Remote Numerics.") {
    val fInt = Remote(1)
    val sInt = Remote(2)
    assert((fInt log sInt).eval)(equalTo(Right(0)))
  },
    test("Test log of Remote Long") {
      val fLong = Remote(1L)
      val sLong = Remote(2L)
      assert((fLong log sLong).eval)(equalTo(Right(0L)))
    },
    test("Test log of Remote Short") {
      val fShort = Remote(1.toShort)
      val sShort = Remote(2.toShort)
      assert((fShort log sShort).eval)(equalTo(Right((0L).toShort)))
    },
    test("Test log of Remote Float") {
      val fFloat = Remote(1.555f)
      val sFloat = Remote(2.333f)
      assert((fFloat log sFloat).eval)(equalTo(Right(0.5211272f)))
    },
    test("Test log of Remote Double") {
      val fDouble = Remote(1.555657)
      val sDouble = Remote(2.333665)
      assert((fDouble log sDouble).eval)(equalTo(Right(0.5214504484258191)))
    },
    test("Test log of BigInt"){
      val fBigInt = BigInt("111111111111111")
      val sBigInt = BigInt("222222222222222")
      assert((fBigInt log sBigInt).eval)(equalTo(Right(BigInt("0"))))
    },
    test("Test log of BigDecimal"){
      val fBigDecimal = BigDecimal("11111.1111111111")
      val sBigDecimal = BigDecimal("22222.2222222222")
      assert((fBigDecimal log sBigDecimal).eval)(equalTo(Right(BigDecimal("0.9307465578618759"))))
    })

  val suite6 =  suite("NumericLogSpec")(test("Test root of Remote Long") {
    val fLong = Remote(1L)
    val sLong = Remote(2L)
    assert((fLong root sLong).eval)(equalTo(Right(1L)))
  },
  test("Test root of Remote Short") {
    val fShort = Remote(1.toShort)
    val sShort = Remote(2.toShort)
    assert((fShort root sShort).eval)(equalTo(Right((1L).toShort)))
  },
  test("Test root of Remote Float") {
    val fFloat = Remote(1.555f)
    val sFloat = Remote(2.333f)
    assert((fFloat root sFloat).eval)(equalTo(Right(1.2083198f)))
  },
  test("Test root of Remote Double") {
    val fDouble = Remote(1.555657)
    val sDouble = Remote(2.333665)
    assert((fDouble root sDouble).eval)(equalTo(Right(1.2084734191706394)))
  },
  test("Test root of BigInt"){
    val fBigInt = BigInt("111111111111111")
    val sBigInt = BigInt("222222222222222")
    assert((fBigInt root sBigInt).eval)(equalTo(Right(BigInt("1"))))
  },
  test("Test root of BigDecimal"){
    val fBigDecimal = BigDecimal("11111.1111111111")
    val sBigDecimal = BigDecimal("22222.2222222222")
    assert((fBigDecimal root sBigDecimal).eval)(equalTo(Right(BigDecimal("1.0004192944192845"))))
  })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("NumericSpec")(suite1, suite2, suite3, suite4, suite5, suite6)
}
