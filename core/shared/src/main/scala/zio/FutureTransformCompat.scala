/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

private[zio] trait FutureTransformCompat[+A] { this: CancelableFuture[A] =>
  def transform[S](f: Try[A] => Try[S])(implicit executor: ExecutionContext): Future[S] =
    future.transform(f)(executor)

  def transformWith[S](f: Try[A] => Future[S])(implicit executor: ExecutionContext): Future[S] =
    future.transformWith(f)(executor)
}
