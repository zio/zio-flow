package zio.flow

object Example {
  import Constructor._

  // Expr[A] => Expr[(B, A)]

  type OrderId = Int

  lazy val refundOrder: Activity[OrderId, Nothing, Unit] =
    Activity[OrderId, Nothing, Unit]("refund-order", "Refunds an order with the specified orderId", ???, ???, ???)

  val stateConstructor: Constructor[(StateVar[Int], StateVar[Boolean], StateVar[List[String]])] =
    for {
      intVar  <- newVar[Int](0)
      boolVar <- newVar[Boolean](false)
      listVar <- newVar[List[String]](Nil)
    } yield (intVar, boolVar, listVar)

  val orderProcess: ZFlow[OrderId, Nothing, Unit] =
    ZFlow.define("order-process", stateConstructor) { case (intVar, boolVar, listVar) =>
      ZFlow
        .input[OrderId]
        .flatMap(orderId =>
          ZFlow.transaction {
            intVar.update(_ + orderId) *>
              ZFlow.ifThenElse(orderId > 2)(boolVar.set(true), boolVar.set(false)) *>
              refundOrder(orderId) *>
              listVar.set(Nil)
          }
        )
    }
}

object EmailCampaign {
  type City = String

  type Cuisine    = String
  type Review     = Int
  type Restaurant = String

  lazy val getRestaurants: Activity[(City, Cuisine), Nothing, List[Restaurant]] =
    Activity[(City, Cuisine), Nothing, List[Restaurant]](
      "get-restaurants",
      "Gets the restaurants for a given city and cuisine",
      ???,
      ???,
      ???
    )

  lazy val getReviews: Activity[Restaurant, Nothing, Review] =
    Activity[Restaurant, Nothing, Review]("get-reviews", "Gets the reviews of a specified restaurant", ???, ???, ???)

  lazy val sendEmail: Activity[EmailRequest, Throwable, Unit] =
    Activity[EmailRequest, Throwable, Unit]("send-email", "sends an email", ???, ???, ???)

  /**
   * 1. Get reviews of restaurants in a certain category (e.g. "Asian").
   * 2. If there are 2 days of good reviews, we send a coupon to people
   *    in that city for restaurants in that category.
   */
  lazy val emailCampaign: ZFlow[(City, Cuisine), Throwable, Any] = {
    def waitForPositiveReviews(restaurants: Expr[List[Restaurant]]) =
      ZFlow(0).iterate((count: Expr[Int]) =>
        for {
          reviews  <- ZFlow.foreach(restaurants) { restaurant =>
                        getReviews(restaurant)
                      }
          average  <- ZFlow(reviews.sum / reviews.length)
          newCount <- ZFlow.ifThenElse(average > 5)(count + 1, 0)
          _        <- ZFlow.ifThenElse(newCount !== 3)(ZFlow.sleep(Expr.ofDays(1L)), ZFlow.unit)
        } yield newCount
      )(_ < 3)

    for {
      tuple       <- ZFlow.input[(City, Cuisine)]
      restaurants <- getRestaurants(tuple)
      _           <- waitForPositiveReviews(restaurants)
      _           <- sendEmail(???)
    } yield ()
  }
}
