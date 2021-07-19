package zio.flow

import java.net.URI

import zio.flow
import zio.flow.ZFlowMethodSpec.setBoolVarAfterSleep
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.{ DeriveSchema, Schema }
import zio.test.Assertion.equalTo
import zio.test.{ DefaultRunnableSpec, Spec, TestFailure, TestSuccess, ZSpec, assertM }

object GoodcoverUseCase extends DefaultRunnableSpec {

  case class Policy(id: String)

  implicit val policySchema: Schema[Policy]             = DeriveSchema.gen[Policy]
  implicit val emailRequestSchema: Schema[EmailRequest] = DeriveSchema.gen[EmailRequest]

  val emailRequest: Remote[EmailRequest] = Remote(
    EmailRequest(List("evaluatorEmail@gmail.com"), None, List.empty, List.empty, "")
  )

  val policyClaimStatus: Activity[Policy, Boolean] = Activity[Policy, Boolean](
    "get-policy-claim-status",
    "Returns whether or not claim was made on a policy for a certain year",
    Operation.Http[Policy, Boolean](
      new URI("getPolicyClaimStatus.com"),
      "GET",
      Map.empty[String, String],
      implicitly[Schema[Policy]],
      implicitly[Schema[Boolean]]
    ),
    ZFlow.succeed(true),
    ZFlow.unit
  )

  val getFireRisk: Activity[Policy, Double] = Activity[Policy, Double](
    "get-fire-risk",
    "Gets the probability of fire hazard for a particular property",
    Operation.Http[Policy, Double](
      new URI("getFireRiskForProperty.com"),
      "GET",
      Map.empty[String, String],
      implicitly[Schema[Policy]],
      implicitly[Schema[Double]]
    ),
    ZFlow.succeed(0.23),
    ZFlow.unit
  )

  val reminderEmailForManualEvaluation: Activity[flow.EmailRequest, Unit] = Activity(
    "send-reminder-email",
    "Send out emails, can use third party service.",
    Operation.SendEmail("Server", 22),
    ZFlow.unit,
    ZFlow.unit
  )

  val isManualEvalRequired: Activity[(Policy, Double), Boolean] = Activity[(Policy, Double), Boolean](
    "is-manual-evaluation-required",
    "Returns whether or not manual evaluation is required for this policy.",
    Operation.Http[(Policy, Double), Boolean](
      new URI("isManualEvalRequired.com"),
      "GET",
      Map.empty[String, String],
      implicitly[Schema[(Policy, Double)]],
      implicitly[Schema[Boolean]]
    ),
    ZFlow.succeed(true),
    ZFlow.unit
  )

  def waitAndSetEvalDoneToTrue(evaluationDone: RemoteVariable[Boolean]): ZFlow[Any, Nothing, Unit] =
    for {
      boolVar <- evaluationDone
      _       <- ZFlow.sleep(Remote.ofSeconds(3L))
      _       <- boolVar.set(true)
    } yield ()

  def manualEvalReminderFlow(
    manualEvalDone: RemoteVariable[Boolean]
  ): ZFlow[Any, ActivityError, Boolean] = ZFlow.Iterate(
    ZFlow(true),
    (_: Remote[Boolean]) =>
      for {
        bool <- manualEvalDone
        _    <- setBoolVarAfterSleep(bool, 5, true).fork
        _    <- bool.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
        loop <- bool.get
        _    <- ZFlow.log("Send reminder email to evaluator")
        _    <- reminderEmailForManualEvaluation(emailRequest)
      } yield !loop,
    (b: Remote[Boolean]) => b
  )

  def policyPaymentReminderFlow(
    renewPolicy: RemoteVariable[Boolean]
  ): ZFlow[Any, ActivityError, Boolean] = ZFlow.Iterate(
    ZFlow(true),
    (_: Remote[Boolean]) =>
      for {
        _    <- ZFlow.log("Inside policy renewal reminder flow.")
        bool <- renewPolicy
        _    <- setBoolVarAfterSleep(bool, 5, true).fork
        _    <- bool.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
        loop <- bool.get
        _    <- ZFlow.log("Send reminder email to customer for payment")
        _    <- reminderEmailForManualEvaluation(emailRequest)
      } yield !loop,
    (b: Remote[Boolean]) => b
  )

  def createRenewedPolicy: Activity[(Boolean, Double), Option[Policy]] =
    Activity[(Boolean, Double), Option[Policy]](
      "create-renewed-policy",
      "Creates a new Insurance Policy based on external params like previous claim, fire risk etc.",
      Operation.Http[(Boolean, Double), Option[Policy]](
        new URI("createRenewedPolicy.com"),
        "GET",
        Map.empty[String, String],
        implicitly[Schema[(Boolean, Double)]],
        implicitly[Schema[Option[Policy]]]
      ),
      ZFlow.succeed(None),
      ZFlow.unit
    )

  val policy: Remote[Policy] = Remote(Policy("DummyPolicy"))

  val suite1: Spec[Any, TestFailure[ActivityError], TestSuccess] =
    suite("PolicyClaimStatus")(testM("PolicyClaimStatus") {
      val result = (for {
        manualEvalDone    <- ZFlow.newVar("manualEvalDone", false)
        paymentSuccessful <- ZFlow.newVar("paymentSuccessful", false)
        claimStatus       <- policyClaimStatus(policy)
        fireRisk          <- getFireRisk(policy)
        isManualEvalReq   <- isManualEvalRequired(policy, fireRisk)
        _                 <- ZFlow.when(isManualEvalReq)(manualEvalReminderFlow(manualEvalDone))
        policyOption      <- createRenewedPolicy(claimStatus, fireRisk)
        _                 <- ZFlow.when(policyOption.isSome)(policyPaymentReminderFlow(paymentSuccessful))
      } yield ()).evaluateInMemForGCExample

      assertM(result)(equalTo(()))
    })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("End to end goodcover use-case performed by in-memory executor")(suite1)
}
