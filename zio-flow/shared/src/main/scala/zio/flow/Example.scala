package zio.flow

object Example {

  import Constructor._

  // Remote[A] => Remote[(B, A)]

  type OrderId = Int

  lazy val refundOrder: Activity[OrderId, Nothing, Unit] =
    Activity[OrderId, Nothing, Unit]("refund-order", "Refunds an order with the specified orderId", ???, ???, ???)

  val stateConstructor: Constructor[(Variable[Int], Variable[Boolean], Variable[List[String]])] =
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
          ZFlow.transaction { _ =>
            intVar.update(_ + orderId) *>
              ZFlow.ifThenElse(orderId > 2)(boolVar.set(true), boolVar.set(false)) *>
              refundOrder(orderId) *>
              listVar.set(Nil)
          }
        )
    }
}

object EmailCampaign {
  type City       = String
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
   * in that city for restaurants in that category.
   */
  lazy val emailCampaign: ZFlow[(City, Cuisine), Throwable, Any] = {
    def waitForPositiveReviews(restaurants: Remote[List[Restaurant]]) =
      ZFlow(0).iterate((count: Remote[Int]) =>
        for {
          reviews  <- ZFlow.foreach(restaurants) { restaurant =>
                        getReviews(restaurant)
                      }
          average  <- ZFlow(reviews.sum / reviews.length)
          newCount <- ZFlow((average > 5).ifThenElse(count + 1, 0))
          _        <- ZFlow.ifThenElse(newCount !== 3)(ZFlow.sleep(Remote.ofDays(1L)), ZFlow.unit)
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

/**
 * A real-world example that models the workflow of uber-eats.
 * The workflow is launched when the user places an order on the app.
 * There are 2 phases to the workflow - 1. Restaurant phase and 2. Rider phase.
 * The Rider phase begins when the Restaurant phase reaches completion.
 */
object UberEatsExample {
  type Restaurant = String
  type Order      = List[(String, Int)]
  type User       = String
  type Address    = String
  type Rider      = String

  sealed trait OrderConfirmationStatus

  object OrderConfirmationStatus {

    case object Confirmed extends OrderConfirmationStatus

    case object Cancelled extends OrderConfirmationStatus

  }

  sealed trait OrderState

  object OrderState {

    case object Waiting extends OrderState

    case object StartedPreparing extends OrderState

    case object FoodPrepared extends OrderState

    case object StartedPacking extends OrderState

    case object FoodPacked extends OrderState

  }

  sealed trait RiderState

  object RiderState {

    case object LookingForRider extends RiderState

    case object RiderAssigned extends RiderState

    case object OutForDelivery extends RiderState

    case object Delivered extends RiderState

  }

  implicit def orderConfirmationStatusSchema: Schema[OrderConfirmationStatus.Confirmed.type] = ???

  lazy val getOrderConfirmationStatus: Activity[(Restaurant, Order), Throwable, OrderConfirmationStatus] =
    Activity[(Restaurant, Order), Throwable, OrderConfirmationStatus](
      "get-order-confirmation-status",
      "Gets whether or not an order is confirmed by the restaurant",
      ???,
      ???,
      ???
    )

  lazy val restaurantOrderStatus: ZFlow[(Restaurant, Order), Throwable, Unit] = ???
  // {
  //   for {
  //     tuple           <- ZFlow.input[(Restaurant, Order)]
  //     orderConfStatus <- getOrderConfirmationStatus(tuple)
  //     _               <- ZFlow.ifThenElse(orderConfStatus === OrderConfirmationStatus.Confirmed)(
  //                          processOrderWorkflow,
  //                          cancelOrderWorkflow
  //                        )
  //   } yield ()
  // }

  lazy val getOrderState: Activity[(User, Restaurant, Order), Throwable, OrderState]         = ???
  lazy val pushOrderStatusNotification: Activity[(User, Restaurant, Order), Throwable, Unit] = ???
  lazy val pushRideStatusNotification: Activity[(User, Address), Throwable, Unit]            = ???
  lazy val assignRider: Activity[(User, Address), Throwable, Rider]                          = ???
  lazy val getRiderState: Activity[(User, Address), Throwable, RiderState]                   = ???

  lazy val processOrderWorkflow: ZFlow[(User, Address, Restaurant, Order), Throwable, (User, Address)] = {
    implicit def schemaOrderStateWaiting: Schema[OrderState] = ???

    implicit val sortableOrderState: Sortable[OrderState] = ???

    def updateOrderState(
      user: Remote[User],
      restaurant: Remote[Restaurant],
      order: Remote[Order]
    ): ZFlow[Any, Throwable, OrderState] =
      ZFlow(OrderState.Waiting: OrderState).iterate((orderState: Remote[OrderState]) =>
        for {
          currOrderState <- getOrderState(user, restaurant, order)
          _              <- ZFlow.ifThenElse(currOrderState !== orderState)(
                              pushOrderStatusNotification(user, restaurant, order),
                              ZFlow.unit
                            )
          _              <- ZFlow.sleep(Remote.ofMinutes(2L))
        } yield currOrderState
      )(_ !== (OrderState.FoodPacked: OrderState))

    for {
      tuple4 <- ZFlow.input[(User, Address, Restaurant, Order)]
      _      <- updateOrderState(tuple4._1, tuple4._3, tuple4._4)
    } yield (tuple4._1, tuple4._2)
  }

  def riderWorkflow(tuple2: Remote[(User, Address)]): ZFlow[Any, Throwable, Any] = {
    implicit def schemaRiderState: Schema[RiderState] = ???

    implicit val sortableRiderState: Sortable[RiderState] = ???

    def updateRiderState(rider: Remote[Rider], address: Remote[Address]): ZFlow[Any, Throwable, RiderState] =
      ZFlow(RiderState.RiderAssigned: RiderState).iterate((riderState: Remote[RiderState]) =>
        for {
          currRiderState <- getRiderState(rider, address)
          _              <- ZFlow.ifThenElse(currRiderState !== riderState)(pushRideStatusNotification(tuple2), ZFlow.unit)
          _              <- ZFlow.sleep(Remote.ofMinutes(2L))
        } yield currRiderState
      )(_ !== Remote(RiderState.Delivered: RiderState))

    for {
      rider <- assignRider(tuple2)
      _     <- updateRiderState(rider, tuple2._2)
    } yield ()
  }

  processOrderWorkflow.flatMap(riderWorkflow)
  lazy val cancelOrderWorkflow: ZFlow[(Restaurant, Order), Throwable, Any] = ???
}
