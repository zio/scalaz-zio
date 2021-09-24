/*
 * Copyright 2019-2021 John A. De Goes and the ZIO Contributors
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

package zio.test.mock

import zio.{Has, ZIO}

/**
 * A `Proxy` provides the machinery to map mocked invocations to predefined results
 * and check some constraints on the way.
 */
abstract class Proxy {

  def invoke[RIn <: Has[_], ROut, Input, Error, Value](
    capability: Capability[RIn, Input, Error, Value],
    input: Input
  ): ZIO[ROut, Error, Value]

  final def apply[RIn <: Has[_], ROut, Error, Value](
    capability: Capability[RIn, Unit, Error, Value]
  ): ZIO[ROut, Error, Value] =
    invoke(capability, ())

  final def apply[RIn <: Has[_], ROut, Error, Value, A](
    capability: Capability[RIn, A, Error, Value],
    a: A
  ): ZIO[ROut, Error, Value] =
    invoke(capability, a)

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B](
    capability: Capability[RIn, (A, B), Error, Value],
    a: A,
    b: B
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C](
    capability: Capability[RIn, (A, B, C), Error, Value],
    a: A,
    b: B,
    c: C
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D](
    capability: Capability[RIn, (A, B, C, D), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E](
    capability: Capability[RIn, (A, B, C, D, E), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F](
    capability: Capability[RIn, (A, B, C, D, E, F), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G](
    capability: Capability[RIn, (A, B, C, D, E, F, G), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N,
    o: O
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N,
    o: O,
    p: P
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N,
    o: O,
    p: P,
    q: Q
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N,
    o: O,
    p: P,
    q: Q,
    r: R
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N,
    o: O,
    p: P,
    q: Q,
    r: R,
    s: S
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N,
    o: O,
    p: P,
    q: Q,
    r: R,
    s: S,
    t: T
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N,
    o: O,
    p: P,
    q: Q,
    r: R,
    s: S,
    t: T,
    u: U
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u))

  final def apply[RIn <: Has[_], ROut, Error, Value, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V](
    capability: Capability[RIn, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V), Error, Value],
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I,
    j: J,
    k: K,
    l: L,
    m: M,
    n: N,
    o: O,
    p: P,
    q: Q,
    r: R,
    s: S,
    t: T,
    u: U,
    v: V
  ): ZIO[ROut, Error, Value] =
    invoke(capability, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v))
}
