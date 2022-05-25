package zio.flow.utils

import zio._
import zio.flow.{Activity, ActivityError, Operation, OperationExecutor, ZFlow}
import zio.schema.Schema

import java.net.URI

object MockHelpers {
  val mockActivity: Activity[Unit, Int] =
    Activity(
      "Test Activity",
      "Mock activity created for test",
      Operation.Http[Unit, Int](
        new URI("testUrlForActivity.com"),
        "GET",
        Map.empty[String, String],
        Schema.fail("No schema"),
        implicitly[Schema[Int]]
      ),
      ZFlow.succeed(12),
      ZFlow.succeed(15)
    )

  object mockOpExec extends OperationExecutor[Any] {
    override def execute[I, A](input: I, operation: Operation[I, A]): ZIO[Any, ActivityError, A] =
      Console
        .printLine("Activity processing")
        .mapBoth(error => ActivityError("Failed to write to console", Some(error)), _ => input.asInstanceOf[A])
  }
}
