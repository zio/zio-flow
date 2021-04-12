package zio.flow

import java.time.Period

/**
 * 1. Get all policies that will expire in the next 60 days.
 * 2. For each of these policies, do the following -
 * 2.1 Make multiple calls to internal/external services to evaluate if the buyer's risk has changed.
 * This determination is based on a bunch of different params -
 * 2.1.1 Have they claimed the policy in the past year?
 * 2.1.2 External (possibly flaky) service call to assess fire-risk of the property (based on address).
 * 2.1.3 Is manual evaluation required here? {based on the params from above service calls}
 * 2.1.3.1 If manual evaluation is required, send reminder email to staff. They can set a variable once manual evaluation is completed.
 * Otherwise, send email reminders every 2 days
 * 2.2 Calculate - how many days to 45 days before policy renewal. And sleep till that period.
 * 2.3 Check if the policy renewal is offered or not (function call) along with the terms of the new policy.
 * 2.4 When there are 45 days remaining, send an email to the buyer offering renewal of policy. They can respond with `Renew/ Do not Renew`.
 * A variable is set when they respond.
 * 2.4 Send reminder emails every 2 days until the customer responds or time elapses
 * 2.5 If the client wants to renew the policy, call payment service to deduct payment for this PolicyBuyer.
 * Retry for 30 days or until it succeeds.
 */
object PolicyRenewalExample {
  type PolicyId        = String
  type PropertyAddress = String
  type Email           = String
  type PaymentMethod   = String
  type Year            = Int
  type Probability     = Float

  sealed trait EmailContent

  object EmailContent {

    case object PolicyEvalEmail extends EmailContent

    case object PolicyRenewEmail extends EmailContent

  }

  implicit def emailContentSchema: Schema[EmailContent] = ???

  case class Policy(id: PolicyId, address: PropertyAddress, userEmail: Email, evaluatorEmail: Email)

  case class Buyer(id: String, address: PropertyAddress, email: Email)

  implicit val policySchema: Schema[Policy] = ???
  implicit val buyerSchema: Schema[Buyer]   = ???

  def policyClaimStatus: Activity[Policy, Boolean] =
    Activity[Policy, Boolean](
      "get-policy-claim-status",
      "Returns whether or not claim was made on a policy for a certain year",
      ???,
      ???,
      ???
    )

  def makePayment: Activity[Policy, Unit] =
    Activity[Policy, Unit](
      "make-payment",
      "Make payment",
      ???,
      ???,
      ???
    )

  def getFireRisk: Activity[Policy, Probability] =
    Activity[Policy, Probability](
      "get-fire-risk",
      "Gets the probability of fire hazard for a particular property",
      ???,
      ???,
      ???
    )

  def createRenewedPolicy: Activity[(Boolean, Probability), Option[Policy]] =
    Activity[(Boolean, Probability), Option[Policy]](
      "create-renewed-policy",
      "Creates a new Insurance Policy based on external params like previous claim, fire risk etc.",
      ???,
      ???,
      ???
    )

  def isManualEvaluationRequired: Activity[(Boolean, Probability), Boolean] =
    Activity[(Boolean, Probability), Boolean](
      "is-manual-evaluation-required",
      "Returns whether or not manual evaluation is required for this policy.",
      ???,
      ???,
      ???
    )

  def sendReminderEmailToEvaluator: Activity[Policy, Unit] =
    Activity[Policy, Unit](
      "send-reminder-email",
      "Send out emails, can use third party service.",
      ???,
      ???,
      ???
    )

  def sendReminderEmailToInsured: Activity[Policy, Unit] =
    Activity[Policy, Unit](
      "send-reminder-email",
      "Send out emails, can use third party service.",
      ???,
      ???,
      ???
    )

  def sendEmail: Activity[Any, Unit] =
    Activity[Any, Unit](
      "send-reminder-email",
      "Send out emails, can use third party service.",
      ???,
      ???,
      ???
    )

  def attemptPayment: Activity[Buyer, Unit] =
    Activity[Buyer, Unit](
      "is-manual-evaluation-required",
      "Returns whether or not manual evaluation is required for this policy.",
      ???,
      ???,
      ???
    )

  def manualEvalReminderFlow(
    policy: Remote[Policy],
    evaluationDone: RemoteVariable[Boolean]
  ): ZFlow[Any, ActivityError, Any] =
    ZFlow.doWhile {
      for {
        option <- evaluationDone.waitUntil(_ === true).timeout(Remote.ofDays(1L))
        loop   <- option.isNone.toFlow
        _      <- ZFlow.when(loop)(sendReminderEmailToEvaluator(policy))
      } yield loop
    }

  def policyRenewalReminderFlow(
    policy: Remote[Policy],
    buyerResponded: RemoteVariable[Option[Boolean]]
  ): ZFlow[Any, ActivityError, Any] =
    ZFlow.doWhile {
      for {
        option <- buyerResponded.waitUntil(_.isSome).timeout(Remote.ofDays(2L))
        loop   <- option.isNone.toFlow
        _      <- ZFlow.when(loop)(sendReminderEmailToInsured(policy))
      } yield loop
    }

  lazy val paymentFlow: ZFlow[Buyer, ActivityError, Boolean] =
    ZFlow.newVar("payment-successful", false).flatMap { paymentSuccessful =>
      ZFlow.doWhile {
        for {
          user   <- ZFlow.input[Buyer]
          option <- paymentSuccessful.waitUntil(_ === true).timeout(Remote.ofDays(1L))
          loop   <- option.isNone.toFlow
          _      <- ZFlow.when(loop)(attemptPayment(user))
        } yield loop
      }.timeout(Remote.ofDays(30L)).map(_.isSome)
    }

  lazy val getPoliciesAboutToExpire: Activity[Period, List[Policy]] =
    Activity[Period, List[Policy]](
      "get-expiring-policies",
      "gets a list of all Policies that are about to expire in `Period` timePeriod",
      ???,
      ???,
      ???
    )

  val stateConstructor: ZFlow[Any, Nothing, (Variable[Boolean], Variable[Option[Boolean]])] =
    for {
      evaluationDone <- ZFlow.newVar[Boolean]("evaluationDone", false)
      renewPolicy    <- ZFlow.newVar[Option[Boolean]]("renewPolicy", None)
    } yield (evaluationDone, renewPolicy)

  lazy val policyRenewalFlow: ZFlow[PaymentMethod with Policy, ActivityError, Unit] =
    stateConstructor.flatMap { tuple =>
      val manualEvalDone = tuple._1
      val renewPolicy    = tuple._2

      def performPolicyRenewal(
        policy: Remote[Policy],
        manualEvalDone: RemoteVariable[Boolean],
        renewPolicy: RemoteVariable[Option[Boolean]]
      ): ZFlow[PaymentMethod, ActivityError, Unit] =
        for {
          claimStatus     <- policyClaimStatus(policy)
          fireRisk        <- getFireRisk(policy)
          isManualEvalReq <- isManualEvaluationRequired(claimStatus, fireRisk)
          _               <- ZFlow.when(isManualEvalReq)(manualEvalReminderFlow(policy, manualEvalDone))
          policyOption    <- createRenewedPolicy(claimStatus, fireRisk)
          _               <- policyOption.handleOption(ZFlow.unit, (p: Remote[Policy]) => policyRenewalReminderFlow(p, renewPolicy))
          renew           <- renewPolicy.get
          _               <- renew.handleOption(ZFlow.unit, (r: Remote[Boolean]) => r.ifThenElse(paymentFlow, ZFlow.unit))
        } yield ()

      for {
        policies <- getPoliciesAboutToExpire(Remote(Period.ofDays(60)))
        _        <- ZFlow.foreach(policies) { policy =>
                      performPolicyRenewal(policy, manualEvalDone, renewPolicy)
                    }
      } yield ()
    }
}
