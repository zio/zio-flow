package zio.flow

import java.net.URI

import zio.flow.ZFlow
import zio.flow.utils.ZFlowAssertionSyntax.InMemoryZFlowAssertion
import zio.schema.{ DeriveSchema, Schema }
import zio.test.Assertion.equalTo
import zio.test.{ DefaultRunnableSpec, Spec, TestFailure, TestSuccess, ZSpec, assertM }

object GoodcoverUseCase extends DefaultRunnableSpec {

  case class Policy(id: String)

  implicit val policySchema: Schema[Policy] = DeriveSchema.gen[Policy]

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
    policy: Remote[Policy],
    evaluationDone: RemoteVariable[Boolean]
  ): ZFlow[Any, Nothing, Any] = ZFlow.doWhile {
    for {
      _    <- ZFlow.log("Inside manual eval reminder workflow.")
      _    <- waitAndSetEvalDoneToTrue(evaluationDone).fork
      _    <- evaluationDone.waitUntil(_ === true).timeout(Remote.ofSeconds(1L))
      loop <- evaluationDone.get
      _    <- ZFlow.log("Sending reminder email.")
    } yield loop
  }

  val policy: Remote[Policy]                                     = Remote(Policy("DummyPolicy"))
  val suite1: Spec[Any, TestFailure[ActivityError], TestSuccess] =
    suite("PolicyClaimStatus")(testM("PolicyClaimStatus") {
      val result = (for {
        manualEvalDone  <- ZFlow.newVar("manualEvalDone", false)
        claimStatus     <- policyClaimStatus(policy)
        fireRisk        <- getFireRisk(policy)
        isManualEvalReq <- isManualEvalRequired(policy, fireRisk)
        _               <- manualEvalReminderFlow(policy, manualEvalDone)
      } yield isManualEvalReq).evaluateInMemForGCExample

      assertM(result)(equalTo(true))
    })

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("End to end goodcover use-case performed by in-memory executor")(suite1)
}
