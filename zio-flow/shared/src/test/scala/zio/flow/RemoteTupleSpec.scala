package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test._

object RemoteTupleSpec extends ZIOSpecDefault {

  override def spec: Spec[Annotations, TestFailure[Any], TestSuccess] =
    suite("RemoteTupleSpec")(
      test("Tuple2") {
        val tuple2 = Remote((1, "A"))
        BoolAlgebra.all(
          tuple2._1 <-> 1,
          tuple2._2 <-> "A"
        )
      },
      test("Tuple3") {
        val tuple3 = Remote((1, "A", true))
        BoolAlgebra.all(
          tuple3._1 <-> 1,
          tuple3._2 <-> "A",
          tuple3._3 <-> true
        )
      },
      test("Tuple4") {
        val tuple4 = Remote((1, "A", true, 10.5))
        BoolAlgebra.all(
          tuple4._1 <-> 1,
          tuple4._2 <-> "A",
          tuple4._3 <-> true,
          tuple4._4 <-> 10.5
        )
      },
      test("Tuple5") {
        val tuple5 = Remote((1, "A", true, 10.5, "X"))
        BoolAlgebra.all(
          tuple5._1 <-> 1,
          tuple5._2 <-> "A",
          tuple5._3 <-> true,
          tuple5._4 <-> 10.5,
          tuple5._5 <-> "X"
        )
      }
    )

}
