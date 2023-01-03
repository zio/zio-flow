/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

import zio.ZIOAspect
import zio.metrics.Metric
import zio.metrics.MetricKeyType.Histogram

package object metrics {
  def remoteEvaluationCount(name: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .counter("zioflow_remote_evals")
      .tagged("name", name)
      .trackAll(1)

  def remoteEvaluationTimeMillis(name: String): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    Metric
      .histogram("zioflow_remote_eval_time_ms", Histogram.Boundaries.exponential(10, 2, 14))
      .tagged("name", name)
      .trackDurationWith(_.toMillis.toDouble)

}
