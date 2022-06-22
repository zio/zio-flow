package zio.flow.remote

import zio.{ZIO, ZLayer}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{Remote, RemoteContext}
import zio.flow.Remote.{BinaryNumeric, DurationBetweenInstants, DurationFromBigDecimal, DurationFromLongs, DurationFromString, DurationMultipliedBy, DurationPlusDuration, UnaryFractional, UnaryNumeric, InstantFromLongs, InstantFromString, InstantToTuple, InstantPlusDuration, InstantTruncate, durationString, fractionalString, numericString, instantString, remoteToString}
import zio.test.{Spec, TestEnvironment, TestResult}
import zio.flow.remote.numeric.{BinaryNumericOperator, Fractional, Numeric, UnaryFractionalOperator, UnaryNumericOperator}
import zio.schema.DefaultJavaTimeSchemas

import java.math.BigDecimal
import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit

object RemoteAsStringSpec extends RemoteSpecBase with DefaultJavaTimeSchemas {
  val suite: Spec[TestEnvironment, Any] =
    suite("RemoteAsStringSpec")(
      test("remoteToString") {
        ZIO
          .collectAll(
            List(
              Remote.remoteToString(Remote("A")) <-> "A",
              Remote.remoteToString(Remote(99)) <-> "99"
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("numericString") {
        ZIO
          .collectAll(
            List(
              numericString(remoteToString[Int, Int])
              (Remote(99))
                <-> "99",
              numericString(remoteToString[Int, Int])
              (Remote(-99))
                <-> "-99",
              numericString(remoteToString[Int, Int])
              (UnaryNumeric(Remote(99), implicitly[Numeric[Int]], UnaryNumericOperator.Neg))
                <-> "-(99)",
              numericString(remoteToString[Int, Int])
              (UnaryNumeric(Remote(-99), implicitly[Numeric[Int]], UnaryNumericOperator.Neg))
                <-> "-(-99)",
              numericString(remoteToString[Int, Int])
              (UnaryNumeric(Remote(99), implicitly[Numeric[Int]], UnaryNumericOperator.Abs))
                <-> "abs(99)",
              numericString(remoteToString[Int, Int])
              (UnaryNumeric(Remote(99), implicitly[Numeric[Int]], UnaryNumericOperator.Floor))
                <-> "floor(99)",
              numericString(remoteToString[Int, Int])
              (UnaryNumeric(Remote(99), implicitly[Numeric[Int]], UnaryNumericOperator.Ceil))
                <-> "ceil(99)",
              numericString(remoteToString[Int, Int])
              (UnaryNumeric(Remote(99), implicitly[Numeric[Int]], UnaryNumericOperator.Round))
                <-> "round(99)",
              numericString(remoteToString[Int, Int])
              (UnaryNumeric(Remote(-99), implicitly[Numeric[Int]], UnaryNumericOperator.Round))
                <-> "round(-99)",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Add))
                <-> "1+2",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Mul))
                <-> "1*2",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Div))
                <-> "1/2",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Mod))
                <-> "1%2",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Pow))
                <-> "1^2",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Root))
                <-> "root(1,2)",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Log))
                <-> "log(1,2)",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Min))
                <-> "min(1,2)",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(1), Remote(2), implicitly[Numeric[Int]], BinaryNumericOperator.Max))
                <-> "max(1,2)",
              numericString(remoteToString[Int, Int])
              (BinaryNumeric(Remote(-1), Remote(-2), implicitly[Numeric[Int]], BinaryNumericOperator.Add))
                <-> "-1+-2",
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("fractionalString") {
        ZIO
          .collectAll(
            List(
              fractionalString(remoteToString[Float, Float])
              (Remote(99))
                <-> "99.0",
              fractionalString(remoteToString[Float, Float])
              (Remote(-99))
                <-> "-99.0",
              fractionalString(remoteToString[Float, Float])
              (UnaryFractional(Remote[Float](99), implicitly[Fractional[Float]], UnaryFractionalOperator.Sin))
                <-> "sin(99.0)",
              fractionalString(remoteToString[Float, Float])
              (UnaryFractional(Remote[Float](99), implicitly[Fractional[Float]], UnaryFractionalOperator.ArcSin))
                <-> "arcsin(99.0)",
              fractionalString(remoteToString[Float, Float])
              (UnaryFractional(Remote[Float](99), implicitly[Fractional[Float]], UnaryFractionalOperator.ArcTan))
                <-> "arctan(99.0)",
              fractionalString(remoteToString[Float, Float])
              (UnaryFractional(Remote[Float](-99.1234f), implicitly[Fractional[Float]], UnaryFractionalOperator.Sin))
                <-> "sin(-99.1234)",
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("durationString") {
        ZIO
          .collectAll(
            List(
              durationString(remoteToString[Int, Int])
              (Remote(99))
                <-> "99",
              durationString(remoteToString[Duration, Duration])
              (DurationFromString(Remote("34.5 seconds")))
                <-> "34.5 seconds",
              durationString(remoteToString[Duration, Duration])
              (DurationBetweenInstants(Remote(Instant.ofEpochSecond(1656500000)), Remote(Instant.ofEpochSecond(1656586400))))
                <-> "(1656500000 epoch seconds,0 nanoseconds) <-> (1656586400 epoch seconds,0 nanoseconds)",
              durationString(remoteToString[Duration, Duration])
              (DurationFromBigDecimal(Remote[BigDecimal](BigDecimal.valueOf(1656500000L))))
                <-> "1656500000 seconds",
              durationString(remoteToString[Duration, Duration])
              (DurationFromLongs(Remote(1656500000L), Remote(1656586400L)))
                <-> "(1656500000 seconds,1656586400 nanosecond adjustment)",
              durationString(remoteToString[Duration, Duration])
              (DurationPlusDuration(Remote(Duration.ofSeconds(1656500000L)), Remote(Duration.ofSeconds(1656586400L))))
                <-> "DurationPlusDuration(PT460138H53M20S,PT460162H53M20S)",
              durationString(remoteToString[Duration, Duration])
              (DurationMultipliedBy(Remote(Duration.ofSeconds(1656500000L)), Remote(12L)))
                <-> "DurationMultipliedBy(PT460138H53M20S,12)",
              remoteToString[Duration, Duration]
              (Duration.parse("PT34.5S"))
                <-> "PT34.5S",
            )
          )
          .map(TestResult.all(_: _*))
      },
      test("instantString") {
        ZIO
          .collectAll(
            List(
              instantString(remoteToString[Int, Int])
              (Remote(99))
                <-> "99",
              instantString(remoteToString[Instant, Instant])
              (InstantFromLongs(1656500000L, 1656586400L))
                <-> "(1656500000 seconds,1656586400 nanoseconds)",
              instantString(remoteToString[Instant, Instant])
              (InstantFromString("2007-12-03T10:15:30.00Z"))
                <-> "2007-12-03T10:15:30.00Z",
              instantString(remoteToString[(Long, Int), (Long, Int)])
              (InstantToTuple(Remote(Instant.ofEpochSecond(1656500000))))
                <-> "(1656500000 epoch seconds,0 nanoseconds)",
              instantString(remoteToString[Instant, Instant])
              (InstantPlusDuration(Remote(Instant.ofEpochSecond(1656500000)), Duration.ofSeconds(1656500000L)))
                <-> "(3313000000 epoch seconds,0 nanoseconds)",
              instantString(remoteToString[Instant, Instant])
              (InstantTruncate(Remote(Instant.ofEpochSecond(1656500000)), ChronoUnit.SECONDS))
                <-> "(1656500000 epoch seconds,0 nanoseconds)",
              remoteToString[Instant, Instant]
              (InstantFromLongs(1656500000L, 1656586400L))
                <-> "2022-06-29T10:53:21.656586400Z",
            )
          )
          .map(TestResult.all(_: _*))
      },
    ).provide(ZLayer(RemoteContext.inMemory))

  override def spec = suite("RemoteAsStringSpec")(suite)
}
