package zio.flow.utils

import java.net.URI
import zio.clock.Clock
import zio.console.Console
import zio.flow.GoodcoverUseCase.Policy
import zio.flow.ZFlowExecutor.InMemory
import zio.flow.{ActivityError, Operation, OperationExecutor}
import zio.{Has, Ref, ZIO, console}

object MocksForGCExample {

  def mockOpExec2(map: Map[URI, Any]): OperationExecutor[Console with Clock] =
    new OperationExecutor[Console with Clock] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Console with Clock, ActivityError, A] =
        operation match {
          case Operation.Http(url, _, _, _, _)   =>
            console.putStrLn(s"Request to : ${url.toString}") *> ZIO.succeed(map.get(url).get.asInstanceOf[A])
          case Operation.SendEmail(server, port) =>
            console.putStrLn("Sending email") *> ZIO.succeed(().asInstanceOf[A])
        }
    }

  val mockInMemoryForGCExample: ZIO[Any, Nothing, InMemory[String, Has[Clock.Service] with Has[Console.Service]]] = Ref
    .make[Map[String, Ref[InMemory.State]]](Map.empty)
    .map(ref =>
      InMemory(
        Has(zio.clock.Clock.Service.live) ++ Has(zio.console.Console.Service.live),
        mockOpExec2(mockResponseMap),
        ref
      )
    )

  private val mockResponseMap: Map[URI, Any] = Map(
    new URI("getPolicyClaimStatus.com")   -> true,
    new URI("getFireRiskForProperty.com") -> 0.23,
    new URI("isManualEvalRequired.com")   -> true,
    new URI("createRenewedPolicy.com") -> Some(Policy("DummyPolicy"))
  )
}
