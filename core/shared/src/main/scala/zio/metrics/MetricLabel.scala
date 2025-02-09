/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
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

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * A `MetricLabel` represents a key value pair that allows analyzing metrics at
 * an additional level of granularity. For example if a metric tracks the
 * response time of a service labels could be used to create separate versions
 * that track response times for different clients.
 */
final case class MetricLabel(key: String, value: String)
