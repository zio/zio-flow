package zio.flow

import java.time.Period

/**
 * 1. Get all policies that will expire in the next 60 days.
 * 2. For each of these policies, do the following -
 * 2.1 Make multiple calls to internal/external services to evaluate if the buyer's risk has changed.
 * This determination is based on a bunch of different params -
 * 2.1.1 Have they claimed the policy in the past year?
 * 2.1.2 External (possibly flaky) service call to assess fire-risk of the property (based on address) - may need retries.
 * 2.1.3 Is manual evaluation required here? {based on the params from above service calls}
 * 2.1.3.1 If manual evaluation is required, send reminder email to staff. They can set a variable once manual evaluation is completed.
 * Otherwise, send email reminders every 2 days
 * 2.2 Calculate - how many days to 45 days before policy renewal. And sleep till that period.
 * 2.3 Check if the policy renewal is offered or not (function call) along with the terms of the new policy.
 * 2.4 When there are 45 days remaining, send an email to the buyer offering renewal of policy. They can respond with `Renew/ Do not Renew`.
 * A variable is set when they respond.
 * 2.4 Send reminder emails every 2 days until the customer responds or time elapses
 * 2.5 If the client wants to renew the policy, get their payment details.
 * 2.6 Call payment service to deduct payment for this user. If this fails 4-5 times, get an alternate payment method from the buyer.
 * Retry for 30 days until it succeeds.
 * 2.7 If policy is successfully renewed, send out emails to all subscribers (landlords etc.)
 */
object PolicyRenewalExample {
  type PolicyId        = String
  type PropertyAddress = String
  type Email           = String
  type PaymentMethod   = String
  type Year            = Int
  type Probability     = Float

  case class Policy(id: PolicyId, address: PropertyAddress, userEmail: Email, evaluatorEmail: Email)

  case class Buyer(id: String, address: PropertyAddress, email: Email)

  implicit val policySchema: Schema[Policy] = ???

  def policyClaimStatus: Activity[Policy, Nothing, Boolean] =
    Activity[Policy, Nothing, Boolean](
      "get-policy-claim-status",
      "Returns whether or not claim was made on a policy for a certain year",
      ???,
      ???,
      ???
    )

  def getFireRisk: Activity[Policy, Throwable, Probability] =
    Activity[Policy, Throwable, Probability](
      "get-fire-risk",
      "Gets the probability of fire hazard for a particular property",
      ???,
      ???,
      ???
    )

  def createRenewedPolicy: Activity[(Boolean, Probability), Throwable, Option[Policy]] =
    Activity[(Boolean, Probability), Throwable, Option[Policy]](
      "create-renewed-policy",
      "Creates a new Insurance Policy based on external params like previous claim, fire risk etc.",
      ???,
      ???,
      ???
    )

  def isManualEvaluationRequired: Activity[(Boolean, Probability), Throwable, Boolean] =
    Activity[(Boolean, Probability), Throwable, Boolean](
      "is-manual-evaluation-required",
      "Returns whether or not manual evaluation is required for this policy.",
      ???,
      ???,
      ???
    )

  def sendReminderEmail: Activity[Policy, Nothing, Unit] =
    Activity[Policy, Nothing, Unit](
      "is-manual-evaluation-required",
      "Returns whether or not manual evaluation is required for this policy.",
      ???,
      ???,
      ???
    )

  def manualEvalReminderFlow(policy: Remote[Policy], evaluationDone: Variable[Boolean]): ZFlow[Any, Nothing, Any] =
    ZFlow.doWhile {
      for {
        option <- evaluationDone.waitUntil(_ === true).timeout(Remote.ofDays(1L))
        loop   <- option.isNone.toFlow
        _      <- ZFlow.when(loop)(sendReminderEmail(policy))
      } yield loop
    }

  def policyRenewalReminderFlow(policy: Remote[Policy]): ZFlow[Email, Nothing, Unit] = ???

  def paymentFlow: ZFlow[PaymentMethod, Throwable, Boolean] = ???

  lazy val getPoliciesDueExpiration: Activity[Period, Throwable, List[Policy]] =
    Activity[Period, Throwable, List[Policy]](
      "get-expiring-policies",
      "gets a list of all Policies that are about to expire in `Period` timePeriod",
      ???,
      ???,
      ???
    )

  lazy val policyRenewalFlow: ZFlow[PaymentMethod with Policy, Throwable, Unit] = {

    def performPolicyRenewal(policy: Remote[Policy]): ZFlow[PaymentMethod, Throwable, Unit] =
      for {
        claimStatus     <- policyClaimStatus(policy)
        fireRisk        <- getFireRisk(policy)
        isManualEvalReq <- isManualEvaluationRequired(claimStatus, fireRisk)
        _               <- ZFlow.when(isManualEvalReq)(manualEvalReminderFlow(policy))
        policyOption    <- createRenewedPolicy(claimStatus, fireRisk)
        _               <- policyOption.handleOption(ZFlow.unit, (p: Remote[Policy]) => policyRenewalReminderFlow(p))
        _               <- paymentFlow
      } yield ()

    for {
      policies <- getPoliciesDueExpiration(Remote(Period.ofDays(60)))
      _        <- ZFlow.foreach(policies) { policy =>
                    performPolicyRenewal(policy)
                  }
    } yield ()
  }
}
