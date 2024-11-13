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

package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.ConcurrentHashMap

private[zio] trait VersionSpecific {

  type EnvironmentTag[A] = izumi.reflect.Tag[A]
  lazy val EnvironmentTag = izumi.reflect.Tag

  type TagK[F[_]] = izumi.reflect.TagK[F]
  lazy val TagK = izumi.reflect.TagK

  type TagKK[F[_, _]] = izumi.reflect.TagKK[F]
  lazy val TagKK = izumi.reflect.TagKK

  type TagK3[F[_, _, _]] = izumi.reflect.TagK3[F]
  lazy val TagK3 = izumi.reflect.TagK3

  type TagK4[F[_, _, _, _]]                                                        = izumi.reflect.TagK4[F]
  type TagK5[F[_, _, _, _, _]]                                                     = izumi.reflect.TagK5[F]
  type TagK6[F[_, _, _, _, _, _]]                                                  = izumi.reflect.TagK6[F]
  type TagK7[F[_, _, _, _, _, _, _]]                                               = izumi.reflect.TagK7[F]
  type TagK8[F[_, _, _, _, _, _, _, _]]                                            = izumi.reflect.TagK8[F]
  type TagK9[F[_, _, _, _, _, _, _, _, _]]                                         = izumi.reflect.TagK9[F]
  type TagK10[F[_, _, _, _, _, _, _, _, _, _]]                                     = izumi.reflect.TagK10[F]
  type TagK11[F[_, _, _, _, _, _, _, _, _, _, _]]                                  = izumi.reflect.TagK11[F]
  type TagK12[F[_, _, _, _, _, _, _, _, _, _, _, _]]                               = izumi.reflect.TagK12[F]
  type TagK13[F[_, _, _, _, _, _, _, _, _, _, _, _, _]]                            = izumi.reflect.TagK13[F]
  type TagK14[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _]]                         = izumi.reflect.TagK14[F]
  type TagK15[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]                      = izumi.reflect.TagK15[F]
  type TagK16[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]                   = izumi.reflect.TagK16[F]
  type TagK17[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]                = izumi.reflect.TagK17[F]
  type TagK18[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]             = izumi.reflect.TagK18[F]
  type TagK19[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]          = izumi.reflect.TagK19[F]
  type TagK20[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]       = izumi.reflect.TagK20[F]
  type TagK21[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]    = izumi.reflect.TagK21[F]
  type TagK22[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]] = izumi.reflect.TagK22[F]

  type LightTypeTag = izumi.reflect.macrortti.LightTypeTag

  private[zio] def taggedIsSubtype(left: LightTypeTag, right: LightTypeTag): Boolean = {
    // NOTE: Prefer get/putIfAbsent pattern as it offers better read performance at the cost of
    // potentially computing `<:<` multiple times during app warmup
    val k = (left, right)
    taggedSubtypes.get(k) match {
      case null =>
        val v = left <:< right
        taggedSubtypes.putIfAbsent(k, v)
        v
      case v => v.booleanValue()
    }
  }

  private[zio] def taggedTagType[A](tagged: EnvironmentTag[A]): LightTypeTag =
    tagged.tag

  /**
   * This method takes a tag for an intersection type and returns a set of tags
   * for each individual type:
   *
   * `Tag[A with B]` should produce `Set(Tag[A], Tag[B])`
   */
  private[zio] def taggedGetServices[A](t: LightTypeTag): Set[LightTypeTag] =
    // NOTE: See `taggedIsSubtype` for implementation notes
    taggedServices.get(t) match {
      case null =>
        val v = t.decompose
        taggedServices.putIfAbsent(t, v)
        v
      case v => v
    }

  private val taggedSubtypes: ConcurrentHashMap[(LightTypeTag, LightTypeTag), java.lang.Boolean] = {
    /*
     * '''NOTE''': Larger maps have lower chance of collision which offers better
     * read performance and smaller chance of entering synchronized blocks during writes
     */
    new ConcurrentHashMap[(LightTypeTag, LightTypeTag), java.lang.Boolean](1024)
  }

  private val taggedServices: ConcurrentHashMap[LightTypeTag, Set[LightTypeTag]] =
    new ConcurrentHashMap[LightTypeTag, Set[LightTypeTag]](256)

  private sealed trait BoxedBool { self =>
    final def value: Boolean = self eq BoxedBool.True
  }
  private object BoxedBool {
    case object True  extends BoxedBool
    case object False extends BoxedBool
  }
}
