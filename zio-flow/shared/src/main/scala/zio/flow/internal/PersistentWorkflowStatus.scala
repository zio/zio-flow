package zio.flow.internal

import zio.schema.{DeriveSchema, Schema}

sealed trait PersistentWorkflowStatus

object PersistentWorkflowStatus {
  case object Running   extends PersistentWorkflowStatus
  case object Done      extends PersistentWorkflowStatus
  case object Suspended extends PersistentWorkflowStatus

  implicit val schema: Schema[PersistentWorkflowStatus] = DeriveSchema.gen
}
