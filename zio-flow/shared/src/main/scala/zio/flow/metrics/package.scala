package zio.flow

import zio.ZIOAspect
import zio.metrics.MetricKeyType.Histogram
import zio.metrics._

package object metrics {

  val gcTimeMillis: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .histogram("zioflow_gc_time_ms", Histogram.Boundaries.exponential(10, 2, 14))
      .trackDurationWith(_.toMillis.toDouble)

  val gcDeletions: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric.counter("zioflow_gc_deletion").trackAll(1)

  val gcRuns: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] = Metric.counter("zioflow_gc").trackAll(1)
}
