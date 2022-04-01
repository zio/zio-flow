package zio.flow.utils

import zio._
import zio.flow.{ActivityError, Operation, OperationExecutor}

import java.net.URI

object MocksForGCExample {

  def mockOpExec2(map: Map[URI, Any]): OperationExecutor[Console with Clock] =
    new OperationExecutor[Console with Clock] {
      override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Console with Clock, ActivityError, A] =
        operation match {
          case Operation.Http(url, _, _, _, _) =>
            Console
              .printLine(s"Request to : ${url.toString}")
              .mapBoth(
                error => ActivityError("Failed to write to console", Some(error)),
                _ => map(url).asInstanceOf[A]
              )
          case Operation.SendEmail(_, _) =>
            Console
              .printLine("Sending email")
              .mapBoth(error => ActivityError("Failed to write to console", Some(error)), _.asInstanceOf[A])
        }
    }

//  val mockInMemoryForGCExample: ZIO[Any, Nothing, InMemory[String, Clock with Console]] = Ref
//    .make[Map[String, Ref[InMemory.State]]](Map.empty)
//    .map(ref =>
//      InMemory(
//        ZEnvironment(Clock.ClockLive) ++ ZEnvironment(Console.ConsoleLive),
//        ExecutionEnvironment(Serializer.protobuf, Deserializer.protobuf),
//        mockOpExec2(mockResponseMap),
//        ref
//      )
//    )
//
//  private val mockResponseMap: Map[URI, Any] = Map(
//    new URI("getPolicyClaimStatus.com")   -> true,
//    new URI("getFireRiskForProperty.com") -> 0.23,
//    new URI("isManualEvalRequired.com")   -> true,
//    new URI("createRenewedPolicy.com")    -> Some(Policy("DummyPolicy"))
//  )
}
