/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow

import zio.{Duration, ZIOAspect}
import zio.flow.internal.PersistentWorkflowStatus
import zio.metrics.MetricKeyType.{Counter, Gauge, Histogram}
import zio.metrics._

package object metrics {

  /**
   * Counter incremented every time a flow is started to execute (either a new
   * flow or a restarted one)
   */
  def flowStarted(startType: StartType): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .counterInt("zioflow_started_total")
      .tagged("type", startTypeToLabel(startType))
      .trackAll(1)

  /**
   * Gauge representing the actual number of flows in the executor, both running
   * and suspended ones.
   */
  def activeFlows(status: PersistentWorkflowStatus): Metric[Gauge, Int, MetricState.Gauge] =
    Metric
      .gauge("zioflow_active_flows")
      .tagged("status", statusToLabel(status))
      .contramap((count: Int) => count.toDouble)

  /** Counters for each primitive ZFlow operation */
  def operationCount(operationType: String): Metric[Counter, Int, MetricState.Counter] =
    Metric
      .counterInt("zioflow_operations_total")
      .tagged("op_type", operationType)

  /**
   * Counter increased after a transaction is either committed, failed or
   * retried
   */
  def transactionOutcomeCount(transactionOutcome: TransactionOutcome): Metric[Counter, Int, MetricState.Counter] =
    Metric
      .counterInt("zioflow_transactions_total")
      .tagged("outcome", transactionOutcomeToLabel(transactionOutcome))

  /**
   * Counter increased when a flow finishes with either success, failure or
   * death
   */
  def finishedFlowCount(result: FlowResult): Metric[Counter, Int, MetricState.Counter] =
    Metric
      .counterInt("zioflow_finished_flows_total")
      .tagged("result", flowResultToLabel(result))

  /** Histogram of the serialized workflow state snapshots in bytes */
  val serializedFlowStateSize: Metric.Histogram[Int] =
    Metric
      .histogram(
        "zioflow_state_size_bytes",
        Histogram.Boundaries.exponential(512.0, 2.0, 16)
      )
      .contramap((bytes: Int) => bytes.toDouble)

  /**
   * Counter increased when a remote variable is accessed. The access can be
   * read, write or delete and its kind depends on the scope it belongs.
   */
  def variableAccessCount(
    access: VariableAccess,
    kind: VariableKind
  ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .counterInt("zioflow_variable_access_total")
      .tagged(MetricLabel("access", variableAccessToLabel(access)), MetricLabel("kind", variableKindToLabel(kind)))
      .trackAll(1)

  /** Histogram of serialized size of remote variables in bytes */
  def variableSizeBytes(kind: VariableKind): Metric[Histogram, Int, MetricState.Histogram] =
    Metric
      .histogram("zioflow_variable_size_bytes", Histogram.Boundaries.exponential(512.0, 2.0, 16))
      .tagged("kind", variableKindToLabel(kind))
      .contramap((bytes: Int) => bytes.toDouble)

  /**
   * Histogram of the duration between submitting the workflow and completing it
   * (either successful or failed)
   */
  def finishedFlowAge(result: FlowResult): Metric[Histogram, Duration, MetricState.Histogram] =
    Metric
      .histogram("zioflow_finished_flow_age_ms", Histogram.Boundaries.exponential(1000, 2, 20))
      .tagged("result", flowResultToLabel(result))
      .contramap((duration: Duration) => duration.toMillis.toDouble)

  /**
   * Histogram of the total time a workflow was in either running or suspended
   * state during its life, excluding the time when it was persisted but not
   * loaded into an executor.
   */
  def flowTotalExecutionTime(result: FlowResult): Metric[Histogram, Duration, MetricState.Histogram] =
    Metric
      .histogram("zioflow_total_execution_time_ms", Histogram.Boundaries.exponential(1000, 2, 20))
      .tagged("result", flowResultToLabel(result))
      .contramap((duration: Duration) => duration.toMillis.toDouble)

  /** Histogram of time fragments a workflow spends in suspended state */
  val flowSuspendedTime: Metric[Histogram, Duration, MetricState.Histogram] =
    Metric
      .histogram("zioflow_suspended_time_ms", Histogram.Boundaries.exponential(1000, 2, 20))
      .contramap((duration: Duration) => duration.toMillis.toDouble)

  /** Histogram of the time a full garbage collector run takes */
  val gcTimeMillis: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .histogram("zioflow_gc_time_ms", Histogram.Boundaries.exponential(10, 2, 14))
      .trackDurationWith(_.toMillis.toDouble)

  /**
   * Counter for the number of remote variables deleted by the garbage collector
   */
  val gcDeletions: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric.counter("zioflow_gc_deletion").trackAll(1)

  /** Counter for the number of garbage collectorions */
  val gcRuns: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] = Metric.counter("zioflow_gc").trackAll(1)

  sealed trait TransactionOutcome
  object TransactionOutcome {
    case object Success extends TransactionOutcome
    case object Failure extends TransactionOutcome
    case object Retry   extends TransactionOutcome
  }

  sealed trait FlowResult
  object FlowResult {
    case object Success extends FlowResult
    case object Failure extends FlowResult
    case object Death   extends FlowResult
  }

  sealed trait VariableAccess
  object VariableAccess {
    case object Read   extends VariableAccess
    case object Write  extends VariableAccess
    case object Delete extends VariableAccess
  }

  sealed trait VariableKind
  object VariableKind {
    case object TopLevel      extends VariableKind
    case object Forked        extends VariableKind
    case object Transactional extends VariableKind
  }

  sealed trait StartType
  object StartType {
    case object Fresh     extends StartType
    case object Continued extends StartType
  }

  private def statusToLabel(status: PersistentWorkflowStatus): String =
    status match {
      case PersistentWorkflowStatus.Running   => "running"
      case PersistentWorkflowStatus.Suspended => "suspended"
      case PersistentWorkflowStatus.Done      => "done"
    }

  private def transactionOutcomeToLabel(outcome: TransactionOutcome): String =
    outcome match {
      case TransactionOutcome.Success => "success"
      case TransactionOutcome.Failure => "failure"
      case TransactionOutcome.Retry   => "retry"
    }

  private def flowResultToLabel(result: FlowResult): String =
    result match {
      case FlowResult.Success => "success"
      case FlowResult.Failure => "failure"
      case FlowResult.Death   => "death"
    } // TODO: categorize failures (introduce a proper error type first in place of IOException)

  private def variableAccessToLabel(access: VariableAccess): String =
    access match {
      case VariableAccess.Read   => "read"
      case VariableAccess.Write  => "write"
      case VariableAccess.Delete => "delete"
    }

  private def variableKindToLabel(kind: VariableKind): String =
    kind match {
      case VariableKind.TopLevel      => "toplevel"
      case VariableKind.Forked        => "forked"
      case VariableKind.Transactional => "transactional"
    }

  private def startTypeToLabel(startType: StartType): String =
    startType match {
      case StartType.Fresh     => "fresh"
      case StartType.Continued => "continued"
    }
}
