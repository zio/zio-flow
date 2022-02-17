package zio.flow

import zio.ZIO
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteTupleSpec extends RemoteSpecBase {

  override def spec: Spec[TestEnvironment, TestFailure[Nothing], TestSuccess] =
    suite("RemoteTupleSpec")(
      test("Tuple2") {
        val tuple2 = Remote((1, "A"))
        ZIO
          .collectAll(
            List(
              tuple2._1 <-> 1,
              tuple2._2 <-> "A"
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("Tuple3") {
        val tuple3 = Remote((1, "A", true))
        ZIO
          .collectAll(
            List(
              tuple3._1 <-> 1,
              tuple3._2 <-> "A",
              tuple3._3 <-> true
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("Tuple4") {
        val tuple4 = Remote((1, "A", true, 10.5))
        ZIO
          .collectAll(
            List(
              tuple4._1 <-> 1,
              tuple4._2 <-> "A",
              tuple4._3 <-> true,
              tuple4._4 <-> 10.5
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      },
      test("Tuple5") {
        val tuple5 = Remote((1, "A", true, 10.5, "X"))
        ZIO
          .collectAll(
            List(
              tuple5._1 <-> 1,
              tuple5._2 <-> "A",
              tuple5._3 <-> true,
              tuple5._4 <-> 10.5,
              tuple5._5 <-> "X"
            )
          )
          .map(BoolAlgebra.all(_))
          .map(_.get)
      }
    ).provideCustom(RemoteContext.inMemory)

}
