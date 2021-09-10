/*
 * Copyright 2020-2021 John A. De Goes and the ZIO Contributors
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

package zio.metrics

import zio.internal.metrics._

/**
 * A `MetricClient` provides the functionality to consume metrics produced by
 * ZIO applications. `MetricClient` supports two ways of consuming metrics,
 * corresponding to the two ways that third party metrics services use metrics.
 *
 * First, metrics services can poll for the current state of all recorded
 * metrics using the `unsafeSnapshot` method, which provides a snapshot, as of
 * a point in time, of all metrics recorded by the ZIO application.
 *
 * Second, metrics services can install a listener that will be notified every
 * time a metric is updated.
 *
 * `MetricClient` is a lower level interface and is intended to be used by
 * implementers of integrations with third party metrics services but not by
 * end users.
 */
object MetricClient {

  /**
   * Unsafely installs the specified metric listener.
   */
  final def unsafeInstallListener(listener: MetricListener): Unit =
    metricState.installListener(listener)

  /**
   * Unsafely removed the specified metric listener.
   */
  final def unsafeRemoveListener(listener: MetricListener): Unit =
    metricState.removeListener(listener)

  /**
   * Unsafely captures a snapshot of all metrics recorded by the application.
   */
  final def unsafeSnapshot: Map[MetricKey, MetricState] =
    metricState.snapshot
}
