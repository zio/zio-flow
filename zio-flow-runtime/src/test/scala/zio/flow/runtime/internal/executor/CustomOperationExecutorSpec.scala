package zio.flow.runtime.internal.executor

import zio.flow._
import zio.flow.runtime.internal.PersistentExecutor
import zio.flow.runtime.{DurableLog, IndexedStore, KeyValueStore, ZFlowExecutor}
import zio.flow.serialization.{Deserializer, Serializer}
import zio.schema.{DeriveSchema, DynamicValue, Schema, TypeId}
import zio.test.{Spec, TestEnvironment, assertTrue}
import zio.{Chunk, Queue, ZIO, ZLayer}

object CustomOperationExecutorSpec extends PersistentExecutorBaseSpec {

  final case class CustomOp(prefix: String)
  object CustomOp {
    val typeId: TypeId                    = TypeId.parse("zio.flow.runtime.internal.executor.CustomOperationExecutorSpec.CustomOp")
    implicit val schema: Schema[CustomOp] = DeriveSchema.gen[CustomOp]
  }

  def customOp(prefix: String): Operation[String, Unit] =
    Operation.Custom(CustomOp.typeId, DynamicValue(CustomOp(prefix)), Schema[String], Schema[Unit])

  def customActivity(prefix: String): Activity[String, Unit] =
    Activity(
      "custom1",
      "test activity using custom operation",
      customOp(prefix),
      Activity.checkNotSupported,
      Activity.compensateNotSupported
    )

  final class CustomOperationExecutor(queue: Queue[String]) extends OperationExecutor {
    override def execute[Input, Result](
      input: Input,
      operation: Operation[Input, Result]
    ): ZIO[RemoteContext, ActivityError, Result] =
      operation match {
        case Operation.Custom(typeId, operation, _, _) if typeId == CustomOp.typeId =>
          for {
            op <-
              ZIO.fromEither(operation.toTypedValue(CustomOp.schema)).mapError(failure => ActivityError(failure, None))
            _ <- queue.offer(op.prefix + input.asInstanceOf[String])
          } yield ().asInstanceOf[Result]
        case _ =>
          ZIO.fail(ActivityError("Unsupported operation", None))
      }
  }

  override def flowSpec
    : Spec[TestEnvironment with IndexedStore with DurableLog with KeyValueStore with Configuration, Any] =
    suite("CustomOperationExecutorSpec")(
      test("flows can use custom operations") {
        for {
          queue                        <- Queue.unbounded[String]
          flow                          = customActivity("test1:")("input1") *> customActivity("test2:")("input2")
          opExecutor: OperationExecutor = new CustomOperationExecutor(queue)
          _ <- ZFlowExecutor
                 .run(FlowId("test1"), flow)
                 .provideSome[DurableLog with KeyValueStore with Configuration](
                   PersistentExecutor.make(),
                   ZLayer.succeed(opExecutor),
                   ZLayer.succeed(Serializer.json),
                   ZLayer.succeed(Deserializer.json)
                 )
          items <- queue.takeAll
        } yield assertTrue(items == Chunk("test1:input1", "test2:input2"))
      }
    )
}
