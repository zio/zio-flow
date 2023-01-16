package zio.flow.cassandra

import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import zio.ZIOAspect
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram

package object metrics {

  def cassandraSuccess(
    store: String,
    operation: String
  ): ZIOAspect[Nothing, Any, Nothing, Throwable, Nothing, AsyncResultSet] =
    Metric
      .counter("zioflow_cassandra_success_total")
      .tagged("operation", operation)
      .tagged("store", store)
      .trackSuccessWith((_: AsyncResultSet) => 1)

  def cassandraFailure(
    store: String,
    operation: String
  ): ZIOAspect[Nothing, Any, Nothing, Throwable, Nothing, AsyncResultSet] =
    Metric
      .counter("zioflow_cassandra_failure_total")
      .tagged("operation", operation)
      .tagged("store", store)
      .trackErrorWith((_: Throwable) => 1)

  def cassandraLatency(store: String, operation: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .histogram("zioflow_cassandra_latency_seconds", Histogram.Boundaries.exponential(0.01, 2, 14))
      .tagged("operation", operation)
      .tagged("store", store)
      .trackDurationWith(_.toMillis.toDouble / 1000.0)
}
