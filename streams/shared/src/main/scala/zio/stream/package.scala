/*
 * Copyright 2018-2021 John A. De Goes and the ZIO Contributors
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

package object stream {
  type Stream[+E, +A] = ZStream[Any, E, A]
  val Stream = ZStream

  type UStream[+A] = ZStream[Any, Nothing, A]
  val UStream = ZStream

  type Sink[+E, A, +L, +B] = ZSink[Any, E, A, L, B]
  val Sink = ZSink

  type Transducer[+E, -A, +B] = ZTransducer[Any, E, A, B]
  val Transducer = ZTransducer
}
