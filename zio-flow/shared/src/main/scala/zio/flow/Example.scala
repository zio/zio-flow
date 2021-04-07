package zio.flow

object Example {

  import ZFlowState._

  // Remote[A] => Remote[(B, A)]

  type OrderId = Int

  lazy val refundOrder: Activity[OrderId, Unit] =
    Activity[OrderId, Unit]("refund-order", "Refunds an order with the specified orderId", ???, ???, ???)

  val stateConstructor: ZFlowState[(Variable[Int], Variable[Boolean], Variable[List[String]])] =
    for {
      intVar  <- newVar[Int]("intVar", 0)
      boolVar <- newVar[Boolean]("boolVar", false)
      listVar <- newVar[List[String]]("ListVar", Nil)
    } yield (intVar, boolVar, listVar)

  val orderProcess: ZFlow[OrderId, ActivityError, Unit] =
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
        .orDie
    }
}

object EmailCampaign {
  type City       = String
  type Cuisine    = String
  type Review     = Int
  type Restaurant = String

  lazy val getRestaurants: Activity[(City, Cuisine), List[Restaurant]] =
    Activity[(City, Cuisine), List[Restaurant]](
      "get-restaurants",
      "Gets the restaurants for a given city and cuisine",
      ???,
      ???,
      ???
    )

  lazy val getReviews: Activity[Restaurant, Review] =
    Activity[Restaurant, Review]("get-reviews", "Gets the reviews of a specified restaurant", ???, ???, ???)

  lazy val sendEmail: Activity[EmailRequest, Unit] =
    Activity[EmailRequest, Unit]("send-email", "sends an email", ???, ???, ???)

  /**
   * 1. Get reviews of restaurants in a certain category (e.g. "Asian").
   * 2. If there are 2 days of good reviews, we send a coupon to people
   * in that city for restaurants in that category.
   */
  lazy val emailCampaign: ZFlow[(City, Cuisine), ActivityError, Any] = {
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

  lazy val getOrderConfirmationStatus: Activity[(Restaurant, Order), OrderConfirmationStatus] =
    Activity[(Restaurant, Order), OrderConfirmationStatus](
      "get-order-confirmation-status",
      "Gets whether or not an order is confirmed by the restaurant",
      ???,
      ???,
      ???
    )

  def restaurantOrderStatus(
    restaurant: Remote[Restaurant],
    order: Remote[Order]
  ): ZFlow[(User, Address, Restaurant, Order), ActivityError, Unit] =
    for {
      orderConfStatus <- getOrderConfirmationStatus(restaurant, order)
      _               <- ZFlow.ifThenElse(orderConfStatus === OrderConfirmationStatus.Confirmed)(
                           processOrderWorkflow,
                           cancelOrderWorkflow(restaurant, order)
                         )
    } yield ()

  lazy val getOrderState: Activity[(User, Restaurant, Order), OrderState]         = ???
  lazy val pushOrderStatusNotification: Activity[(User, Restaurant, Order), Unit] = ???
  lazy val pushRideStatusNotification: Activity[(User, Address), Unit]            = ???
  lazy val assignRider: Activity[(User, Address), Rider]                          = ???
  lazy val getRiderState: Activity[(User, Address), RiderState]                   = ???

  lazy val processOrderWorkflow: ZFlow[(User, Address, Restaurant, Order), ActivityError, (User, Address)] = {
    implicit def schemaOrderStateWaiting: Schema[OrderState] = ???

    def updateOrderState(
      user: Remote[User],
      restaurant: Remote[Restaurant],
      order: Remote[Order]
    ): ZFlow[Any, ActivityError, OrderState] =
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

  def riderWorkflow(tuple2: Remote[(User, Address)]): ZFlow[Any, ActivityError, Any] = {
    implicit def schemaRiderState: Schema[RiderState] = ???

    def updateRiderState(rider: Remote[Rider], address: Remote[Address]): ZFlow[Any, ActivityError, RiderState] =
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
  lazy val cancelOrderWorkflow: Activity[(Restaurant, Order), Any] = ???
}
