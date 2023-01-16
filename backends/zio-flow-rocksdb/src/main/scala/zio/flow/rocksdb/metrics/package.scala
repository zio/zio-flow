package zio.flow.rocksdb

import zio.ZIOAspect
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram

package object metrics {

  def rocksdbSuccess(store: String, operation: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .counter("zioflow_rocksdb_success_total")
      .tagged("operation", operation)
      .tagged("store", store)
      .trackSuccessWith((_: Any) => 1)

  def rocksdbFailure(store: String, operation: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .counter("zioflow_rocksdb_failure_total")
      .tagged("operation", operation)
      .tagged("store", store)
      .trackErrorWith((_: Any) => 1)

  def rocksdbLatency(store: String, operation: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .histogram("zioflow_rocksdb_latency_seconds", Histogram.Boundaries.exponential(0.001, 2, 14))
      .tagged("operation", operation)
      .tagged("store", store)
      .trackDurationWith(_.toMillis.toDouble / 1000.0)
}
