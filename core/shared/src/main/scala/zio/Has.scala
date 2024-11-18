/*
 * Copyright 2020-2022 John A. De Goes and the ZIO Contributors
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

import scala.annotation.implicitNotFound

/**
 * The trait `Has[A]` is used with ZIO environment to express an effect's
 * dependency on a service of type `A`. For example,
 * `RIO[Has[Console.Service], Unit]` is an effect that requires a
 * `Console.Service` service. Inside the ZIO library, type aliases are provided
 * as shorthands for common services, e.g.:
 *
 * {{{
 * type Console = Has[ConsoleService]
 * }}}
 *
 * Services parameterized on path dependent types are not supported.
 */
final class Has[A] private (
  private val map: Map[LightTypeTag, scala.Any],
  private var cache: Map[LightTypeTag, scala.Any] = Map()
) extends Serializable {
  override def equals(that: Any): Boolean = that match {
    case that: Has[_] => map == that.map
    case _            => false
  }

  override def hashCode: Int = map.hashCode

  override def toString: String = map.mkString("Map(", ",\n", ")")

  /**
   * The size of the environment, which is the number of services contained
   * in the environment. This is intended primarily for testing purposes.
   */
  def size: Int = map.size
}
object Has {
  private val TaggedAnyRef: Tag[AnyRef] = implicitly[Tag[AnyRef]]

  type MustHave[A, B] = A <:< Has[B]

  @implicitNotFound(
    "Currently, your ZLayer produces ${R}, but to use this operator, you " +
      "must produce Has[${R}]. You can either map over your layer, and wrap " +
      "it with the Has(_) constructor, or you can directly wrap your " +
      "service in Has at the point where it is currently being constructed."
  )
  abstract class IsHas[-R] {
    def add[R0 <: R, M: Tag](r: R0, m: M): R0 with Has[M]
    def union[R0 <: R, R1 <: Has[_]: Tag](r: R0, r1: R1): R0 with R1
    def update[R0 <: R, M: Tag](r: R0, f: M => M)(implicit ev: R0 <:< Has[M]): R0
  }
  object IsHas {
    implicit def ImplicitIs[R <: Has[_]]: IsHas[R] =
      new IsHas[R] {
        def add[R0 <: R, M: Tag](r: R0, m: M): R0 with Has[M] = r.add(m)
        def union[R0 <: R, R1 <: Has[_]: Tag](r: R0, r1: R1): R0 with R1 = r.union[R1](r1)
        def update[R0 <: R, M: Tag](r: R0, f: M => M)(implicit ev: R0 <:< Has[M]): R0 = r.update(f)
      }
  }

  @implicitNotFound(
    "The ZLayer operator you are trying to use needs to combine multiple " +
      "services. While services cannot directly be combined, they can be " +
      "combined if first wrapped in the Has data type. Before you use this " +
      "operator, you must ensure the service produced by your layer is " +
      "wrapped in Has."
  )
  abstract class Union[R, R1] {
    def union(r: R, r1: R1): R with R1
  }

  object Union extends LowPriorityUnionImplicits {
    implicit def HasHasUnion[R <: Has[_], R1 <: Has[_]: Tag]: Union[R, R1] =
      new Union[R, R1] {
        def union(r: R, r1: R1): R with R1 =
          r.union[R1](r1)
      }
  }

  abstract class LowPriorityUnionImplicits {
    implicit def HasAnyUnion[R <: Has[_]]: Union[R, Any] =
      new Union[R, Any] {
        def union(r: R, r1: Any): R = r
      }

    implicit def AnyHasUnion[R1 <: Has[_]]: Union[Any, R1] =
      new Union[Any, R1] {
        def union(r: Any, r1: R1): R1 = r1
      }

    implicit val AnyAnyUnion: Union[Any, Any] =
      new Union[Any, Any] {
        def union(r: Any, r1: Any): Any = ()
      }
  }

  @implicitNotFound(
    "The ZLayer operator you are trying to use needs to combine multiple " +
      "services. While services cannot directly be combined, they can be " +
      "combined if first wrapped in the Has data type. Before you use this " +
      "operator, you must ensure the service produced by your layer is " +
      "wrapped in Has."
  )
  abstract class UnionAll[R, R1] {
    def unionAll(r: R, r1: R1): R with R1
  }

  object UnionAll extends LowPriorityUnionAllImplicits {
    implicit def HasHasUnionAll[R <: Has[_], R1 <: Has[_]]: UnionAll[R, R1] =
      new UnionAll[R, R1] {
        def unionAll(r: R, r1: R1): R with R1 =
          r.unionAll[R1](r1)
      }

    @deprecated("Kept for binary compatibility", since = "1.0.19")
    private[zio] def HasHasUnionAll[R <: Has[_], R1 <: Has[_]: Tag]: UnionAll[R, R1] =
      new UnionAll[R, R1] {
        def unionAll(r: R, r1: R1): R with R1 =
          r.unionAll[R1](r1)
      }
  }

  abstract class LowPriorityUnionAllImplicits {
    implicit def HasAnyUnionAll[R <: Has[_]]: UnionAll[R, Any] =
      new UnionAll[R, Any] {
        def unionAll(r: R, r1: Any): R = r
      }

    implicit def AnyHasUnionAll[R1 <: Has[_]]: UnionAll[Any, R1] =
      new UnionAll[Any, R1] {
        def unionAll(r: Any, r1: R1): R1 = r1
      }

    implicit val AnyAnyUnionAll: UnionAll[Any, Any] =
      new UnionAll[Any, Any] {
        def unionAll(r: Any, r1: Any): Any = ()
      }
  }

  implicit final class HasSyntax[Self <: Has[_]](private val self: Self) extends AnyVal {

    def ++[B <: Has[_]: Tag](that: B): Self with B = self.union[B](that)

    /**
     * Adds a service to the environment.
     */
    def add[B](b: B)(implicit tagged: Tag[B]): Self with Has[B] =
      new Has(self.map + (taggedTagType(tagged) -> b)).asInstanceOf[Self with Has[B]]

    /**
     * Retrieves a service from the environment.
     */
    def get[B](implicit ev: Self <:< Has[B], tagged: Tag[B]): B = {
      val tag = taggedTagType(tagged)

      self.map
        .getOrElse(
          tag,
          self.cache.getOrElse(
            tag,
            throw new Error(s"Defect in zio.Has: Could not find ${tag} inside ${self}")
          )
        )
        .asInstanceOf[B]
    }

    /**
     * Prunes the environment to the set of services statically known to be
     * contained within it.
     */
    def prune(implicit tagged: Tag[Self]): Self = {
      val tag = taggedTagType(tagged)
      val set = taggedGetHasServices(tag)

      val missingServices = set -- self.map.keySet
      if (missingServices.nonEmpty) {
        throw new Error(
          s"Defect in zio.Has: ${missingServices} statically known to be contained within the environment are missing"
        )
      }

      if (set.isEmpty) self
      else new Has(filterKeys(self.map)(set)).asInstanceOf[Self]
    }

    /**
     * Combines this environment with the specified environment.
     */
    def union[B <: Has[_]: Tag](that: B): Self with B =
      self.unionAll[B](that.prune)

    /**
     * Combines this environment with the specified environment. In the event
     * of service collisions, which may not be reflected in statically known
     * types, the right hand side will be preferred.
     */
    def unionAll[B <: Has[_]](that: B): Self with B =
      (new Has(self.map ++ that.map)).asInstanceOf[Self with B]

    /**
     * Updates a service in the environment.
     */
    def update[B: Tag](f: B => B)(implicit ev: Self MustHave B): Self =
      self.add(f(get[B]))
  }

  /**
   * Constructs a new environment holding the single service.
   */
  def apply[A: Tag](a: A): Has[A] = new Has[AnyRef](Map(), Map(taggedTagType(TaggedAnyRef) -> (()))).add(a)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag](a: A, b: B): Has[A] with Has[B] = Has(a).add(b)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C): Has[A] with Has[B] with Has[C] =
    Has(a).add(b).add(c)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag](
    a: A,
    b: B,
    c: C,
    d: D
  ): Has[A] with Has[B] with Has[C] with Has[D] =
    Has(a).add(b).add(c).add(d)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag](
    a: A,
    b: B,
    c: C,
    d: D,
    e: E
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] =
    Has(a).add(b).add(c).add(d).add(e)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag](
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] =
    Has(a).add(b).add(c).add(d).add(e).add(f)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag](
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag](
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag](
    a: A,
    b: B,
    c: C,
    d: D,
    e: E,
    f: F,
    g: G,
    h: H,
    i: I
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag, O: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] with Has[O] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n).add(o)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag, O: Tag, P: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] with Has[O] with Has[P] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n).add(o).add(p)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag, O: Tag, P: Tag, Q: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] with Has[O] with Has[P] with Has[Q] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n).add(o).add(p).add(q)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag, O: Tag, P: Tag, Q: Tag, R: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] with Has[O] with Has[P] with Has[Q] with Has[R] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n).add(o).add(p).add(q).add(r)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag, O: Tag, P: Tag, Q: Tag, R: Tag, S: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] with Has[O] with Has[P] with Has[Q] with Has[R] with Has[S] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n).add(o).add(p).add(q).add(r).add(s)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag, O: Tag, P: Tag, Q: Tag, R: Tag, S: Tag, T: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] with Has[O] with Has[P] with Has[Q] with Has[R] with Has[S] with Has[T] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n).add(o).add(p).add(q).add(r).add(s).add(t)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag, O: Tag, P: Tag, Q: Tag, R: Tag, S: Tag, T: Tag, U: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] with Has[O] with Has[P] with Has[Q] with Has[R] with Has[S] with Has[T] with Has[U] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n).add(o).add(p).add(q).add(r).add(s).add(t).add(u)

  /**
   * Constructs a new environment holding the specified services.
   */
  def allOf[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, F: Tag, G: Tag, H: Tag, I: Tag, J: Tag, K: Tag, L: Tag, M: Tag, N: Tag, O: Tag, P: Tag, Q: Tag, R: Tag, S: Tag, T: Tag, U: Tag, V: Tag](
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
  ): Has[A] with Has[B] with Has[C] with Has[D] with Has[E] with Has[F] with Has[G] with Has[H] with Has[I] with Has[J] with Has[K] with Has[L] with Has[M] with Has[N] with Has[O] with Has[P] with Has[Q] with Has[R] with Has[S] with Has[T] with Has[U] with Has[V] =
    Has(a).add(b).add(c).add(d).add(e).add(f).add(g).add(h).add(i).add(j).add(k).add(l).add(m).add(n).add(o).add(p).add(q).add(r).add(s).add(t).add(u).add(v)

  /**
   * Modifies an environment in a scoped way.
   *
   * {{
   * Env.scoped[Logging](decorateLogger(_)) { effect }
   * }}
   */
  def scoped[A: Tag](f: A => A): Scoped[A] = new Scoped(f)

  class Scoped[M: Tag](f: M => M) {
    def apply[R <: Has[M], E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.environment[R].flatMap(env => zio.provide(env.update(f)))
  }

  /**
   * Filters a map by retaining only keys satisfying a predicate.
   */
  private def filterKeys[K, V](map: Map[K, V])(f: K => Boolean): Map[K, V] =
    map.foldLeft[Map[K, V]](Map.empty) {
      case (acc, (key, value)) =>
        if (f(key)) acc + (key -> value) else acc
    }
}
