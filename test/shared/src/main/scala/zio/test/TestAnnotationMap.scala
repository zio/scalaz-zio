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

package zio.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.immutable.Map

/**
 * An annotation map keeps track of annotations of different types.
 */
final class TestAnnotationMap private (private val map: Map[TestAnnotation[Any], AnyRef]) { self =>

  def ++(that: TestAnnotationMap): TestAnnotationMap = {
    val map0 = that.map.foldLeft(self.map) { case (acc, (k, v)) =>
      acc.updated(k, acc.get(k).fold(v)(k.combine(_, v).asInstanceOf[AnyRef]))
    }
    new TestAnnotationMap(map0)
  }

  /**
   * Appends the specified annotation to the annotation map.
   */
  def annotate[V](key: TestAnnotation[V], value: V): TestAnnotationMap =
    update[V](key, key.combine(_, value))

  /**
   * Retrieves the annotation of the specified type, or its default value if
   * there is none.
   */
  def get[V](key: TestAnnotation[V]): V =
    map.get(key.asInstanceOf[TestAnnotation[Any]]).fold(key.initial)(_.asInstanceOf[V])

  private def overwrite[V](key: TestAnnotation[V], value: V): TestAnnotationMap =
    new TestAnnotationMap(map.updated(key.asInstanceOf[TestAnnotation[Any]], value.asInstanceOf[AnyRef]))

  private def update[V](key: TestAnnotation[V], f: V => V): TestAnnotationMap =
    overwrite(key, f(get(key)))

  override def toString: String =
    map.toString
}

object TestAnnotationMap {

  /**
   * An empty annotation map.
   */
  val empty: TestAnnotationMap = new TestAnnotationMap(Map())
}
