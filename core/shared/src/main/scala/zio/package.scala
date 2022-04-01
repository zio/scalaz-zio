/*
 * Copyright 2017-2022 John A. De Goes and the ZIO Contributors
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

import zio.TagVersionSpecific
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.reflect.ClassTag

package object zio
    extends BuildFromCompat
    with EitherCompat
    with IntersectionTypeCompat
    with VersionSpecific
    with DurationModule {

  type ZEnv = Clock with Console with System with Random

  private[zio] type Callback[E, A] = Exit[E, A] => Any

  type IO[+E, +A]   = ZIO[Any, E, A]         // Succeed with an `A`, may fail with `E`        , no requirements.
  type Task[+A]     = ZIO[Any, Throwable, A] // Succeed with an `A`, may fail with `Throwable`, no requirements.
  type RIO[-R, +A]  = ZIO[R, Throwable, A]   // Succeed with an `A`, may fail with `Throwable`, requires an `R`.
  type UIO[+A]      = ZIO[Any, Nothing, A]   // Succeed with an `A`, cannot fail              , no requirements.
  type URIO[-R, +A] = ZIO[R, Nothing, A]     // Succeed with an `A`, cannot fail              , requires an `R`.

  val IO: ZIO.type   = ZIO
  val Task: ZIO.type = ZIO
  val RIO: ZIO.type  = ZIO
  val UIO: ZIO.type  = ZIO
  val URIO: ZIO.type = ZIO

  type RLayer[-RIn, +ROut]  = ZLayer[RIn, Throwable, ROut]
  type URLayer[-RIn, +ROut] = ZLayer[RIn, Nothing, ROut]
  type Layer[+E, +ROut]     = ZLayer[Any, E, ROut]
  type ULayer[+ROut]        = ZLayer[Any, Nothing, ROut]
  type TaskLayer[+ROut]     = ZLayer[Any, Throwable, ROut]

  @deprecated("use Ref.Synchronized", "2.0.0")
  type RefM[A] = Ref.Synchronized[A]
  val RefM: Ref.Synchronized.type = Ref.Synchronized

  type Semaphore = stm.TSemaphore

  type ZTraceElement = Tracer.instance.Type with Tracer.Traced

  trait Tag[A] extends EnvironmentTag[A] {
    def tag: LightTypeTag
  }

  object Tag extends TagVersionSpecific {
    def apply[A](implicit tag0: EnvironmentTag[A], isNotIntersection: IsNotIntersection[A]): Tag[A] =
      new Tag[A] {
        def tag: zio.LightTypeTag = tag0.tag

        override def closestClass: Class[_] = tag0.closestClass
      }
  }

  trait IsNotIntersection[A] extends Serializable

  object IsNotIntersection extends IsNotIntersectionVersionSpecific {
    def apply[A: IsNotIntersection]: IsNotIntersection[A] = implicitly[IsNotIntersection[A]]
  }
}
