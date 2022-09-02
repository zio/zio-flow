package zio.flow.examples

import zio.flow.internal.executor.PersistentExecutorBaseSpec
import zio.{ZNothing, durationInt}
import zio.flow.internal.{DurableLog, IndexedStore, KeyValueStore}
import zio.flow.mock.MockedOperation
import zio.flow.operation.http
import zio.flow.{Activity, ActivityError, EmailRequest, Operation, Remote, RemoteVariableReference, ZFlow}
import zio.schema.{DeriveSchema, Schema}
import zio.test.Assertion.equalTo
import zio.test.{Spec, TestEnvironment, assertTrue}

object GoodcoverUseCase extends PersistentExecutorBaseSpec {

  def setBoolVarAfterSleep(
    remoteBoolVar: Remote[RemoteVariableReference[Boolean]],
    sleepDuration: Long,
    value: Boolean
  ): ZFlow[Any, ZNothing, Unit] = for {
    _ <- ZFlow.sleep(Remote.ofSeconds(sleepDuration))
    _ <- remoteBoolVar.set(value)
  } yield ()

  case class Policy(id: String)
  object Policy {
    implicit val policySchema: Schema[Policy] = DeriveSchema.gen[Policy]
  }

  val emailRequest: Remote[EmailRequest] = Remote(
    EmailRequest(List("evaluatorEmail@gmail.com"), None, List.empty, List.empty, "")
  )

  val policyClaimStatus: Activity[Policy, Boolean] = Activity[Policy, Boolean](
    "get-policy-claim-status",
    "Returns whether or not claim was made on a policy for a certain year",
    Operation.Http[Policy, Boolean](
      "getPolicyClaimStatus.com",
      http.API.get("").input[Policy].output[Boolean]
    ),
    ZFlow.succeed(true),
    ZFlow.unit
  )

  val getFireRisk: Activity[Policy, Double] = Activity[Policy, Double](
    "get-fire-risk",
    "Gets the probability of fire hazard for a particular property",
    Operation.Http[Policy, Double](
      "getFireRiskForProperty.com",
      http.API.get("").input[Policy].output[Double]
    ),
    ZFlow.succeed(0.23),
    ZFlow.unit
  )

  val isManualEvalRequired: Activity[(Policy, Double), Boolean] = Activity[(Policy, Double), Boolean](
    "is-manual-evaluation-required",
    "Returns whether or not manual evaluation is required for this policy.",
    Operation.Http[(Policy, Double), Boolean](
      "isManualEvalRequired.com",
      http.API.get("").input[(Policy, Double)].output[Boolean]
    ),
    ZFlow.succeed(true),
    ZFlow.unit
  )

  def waitAndSetEvalDoneToTrue(evaluationDone: Remote[RemoteVariableReference[Boolean]]): ZFlow[Any, ZNothing, Unit] =
    for {
      boolVar <- evaluationDone
      _       <- ZFlow.sleep(Remote.ofSeconds(3L))
      _       <- boolVar.set(true)
    } yield ()

  def manualEvalReminderFlow(
    manualEvalDone: Remote[RemoteVariableReference[Boolean]]
  ): ZFlow[Any, ActivityError, Boolean] = ZFlow.iterate(
    Remote(true),
    (_: Remote[Boolean]) =>
      for {
        bool <- manualEvalDone
        _    <- setBoolVarAfterSleep(bool, 5, true).fork
        _    <- bool.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
        loop <- bool.get
//           _    <- ZFlow.log("Send reminder email to evaluator")
//           _    <- reminderEmailForManualEvaluation(emailRequest)
      } yield !loop,
    (b: Remote[Boolean]) => b
  )

  def policyPaymentReminderFlow(
    renewPolicy: Remote[RemoteVariableReference[Boolean]]
  ): ZFlow[Any, ActivityError, Boolean] = ZFlow.iterate(
    Remote(true),
    (_: Remote[Boolean]) =>
      for {
        _    <- ZFlow.log("Inside policy renewal reminder flow.")
        bool <- renewPolicy
        _    <- setBoolVarAfterSleep(bool, 5, true).fork
        _    <- bool.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
        loop <- bool.get
//         _    <- ZFlow.log("Send reminder email to customer for payment")
//         _    <- reminderEmailForManualEvaluation(emailRequest)
      } yield !loop,
    (b: Remote[Boolean]) => b
  )

  def createRenewedPolicy: Activity[(Boolean, Double), Option[Policy]] =
    Activity[(Boolean, Double), Option[Policy]](
      "create-renewed-policy",
      "Creates a new Insurance Policy based on external params like previous claim, fire risk etc.",
      Operation.Http[(Boolean, Double), Option[Policy]](
        "createRenewedPolicy.com",
        http.API.get("").input[(Boolean, Double)].output[Option[Policy]]
      ),
      ZFlow.succeed(Remote.none[Policy]),
      ZFlow.unit
    )

  val policy: Remote[Policy] = Remote(Policy("DummyPolicy"))

  override def flowSpec: Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore, Any] =
    suite("End to end goodcover use-case performed by in-memory executor")(
      suite("PolicyClaimStatus")(
        testFlow("PolicyClaimStatus", periodicAdjustClock = Some(1.second)) {
          for {
            manualEvalDone    <- ZFlow.newVar("manualEvalDone", false)
            paymentSuccessful <- ZFlow.newVar("paymentSuccessful", false)
            claimStatus       <- policyClaimStatus(policy)
            fireRisk          <- getFireRisk(policy)
            isManualEvalReq   <- isManualEvalRequired(policy, fireRisk)
            _                 <- ZFlow.when(isManualEvalReq)(manualEvalReminderFlow(manualEvalDone))
            policyOption      <- createRenewedPolicy(claimStatus, fireRisk)
            _                 <- ZFlow.when(policyOption.isSome)(policyPaymentReminderFlow(paymentSuccessful))
          } yield ()
        }(
          assert = { result =>
            assertTrue(result == ())
          },
          mock = MockedOperation.Http[Policy, Boolean](
            urlMatcher = equalTo("getPolicyClaimStatus.com"),
            methodMatcher = equalTo("GET"),
            result = () => true
          ) ++ MockedOperation.Http[Policy, Double](
            urlMatcher = equalTo("getFireRiskForProperty.com"),
            methodMatcher = equalTo("GET"),
            result = () => 0.23
          ) ++ MockedOperation.Http[Policy, Boolean](
            urlMatcher = equalTo("isManualEvalRequired.com"),
            methodMatcher = equalTo("GET"),
            result = () => true
          ) ++ MockedOperation.Http[(Boolean, Double), Option[Policy]](
            urlMatcher = equalTo("createRenewedPolicy.com"),
            methodMatcher = equalTo("GET"),
            inputMatcher = equalTo((true, 0.23)),
            result = () => Some(Policy("test policy"))
          )
        )
      )
    )
}
