package zio.flow

object Example {
  import Constructor._

  // Expr[A] => Expr[(B, A)]

  type OrderId = Int

  lazy val refundOrder: Activity[OrderId, Nothing, Unit] =
    Activity.Effect("refund-order", ???, ???, ???, "Refunds an order with the specified orderId")

  val stateConstructor: Constructor[(StateVar[Int], StateVar[Boolean], StateVar[List[String]])] =
    for {
      intVar  <- newVar[Int](0)
      boolVar <- newVar[Boolean](false)
      listVar <- newVar[List[String]](Nil)
    } yield (intVar, boolVar, listVar)

  val orderProcess =
    ZFlow.define("order-process", stateConstructor) { case (intVar, boolVar, listVar) =>
      ZFlow
        .input[OrderId]
        .flatMap(orderId =>
          ZFlow.transaction {
            intVar.update(_ + orderId).toFlow *>
              (orderId > 2).ifThenElse(boolVar.set(true), boolVar.set(false)).toFlow *>
              refundOrder.run(orderId) *>
              listVar.set(Nil)
          }
        )
    }
}
