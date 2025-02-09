/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

/**
 * `ZState[S]` models a value of type `S` that can be read from and written to
 * during the execution of an effect. The idiomatic way to work with `ZState` is
 * as part of the environment using operators defined on `ZIO`. For example:
 *
 * {{{
 * final case class MyState(counter: Int)
 *
 * for {
 *   _     <- ZIO.updateState[MyState](state => state.copy(counter = state.counter + 1))
 *   count <- ZIO.getStateWith[MyState](_.counter)
 * } yield count
 * }}}
 *
 * Because `ZState` is typically used as part of the environment, it is
 * recommended to define your own state type `S` such as `MyState` above rather
 * than using a type such as `Int` to avoid the risk of ambiguity.
 *
 * To run a stateful workflow, use the `ZIO.stateful` operator to allocate the
 * initial state.
 */
sealed trait ZState[S] {

  /**
   * Gets the current state.
   */
  def get(implicit trace: Trace): UIO[S]

  /**
   * Sets the state to the specified value.
   */
  def set(s: S)(implicit trace: Trace): UIO[Unit]

  /**
   * Updates the state with the specified function.
   */
  def update(f: S => S)(implicit trace: Trace): UIO[Unit]
}

object ZState {

  /**
   * A layer that allocates the initial state of a stateful workflow.
   */
  def initial[S: EnvironmentTag](s: => S)(implicit trace: Trace): ZLayer[Any, Nothing, ZState[S]] =
    initialPatch(s, Differ.update[S])

  /**
   * A layer that allocates the initial state of a stateful workflow, using the
   * specified patch type to combine updates to the state by different fibers in
   * a compositional way.
   */
  def initialPatch[State: EnvironmentTag, Patch](state: => State, differ: => Differ[State, Patch])(implicit
    trace: Trace
  ): ZLayer[Any, Nothing, ZState[State]] =
    ZLayer.scoped {
      for {
        fiberRef <- FiberRef.makePatch(state, differ)
      } yield new ZState[State] {
        def get(implicit trace: Trace): UIO[State] =
          fiberRef.get
        def set(state: State)(implicit trace: Trace): UIO[Unit] =
          fiberRef.set(state)
        def update(f: State => State)(implicit trace: Trace): UIO[Unit] =
          fiberRef.update(f)
      }
    }
}
