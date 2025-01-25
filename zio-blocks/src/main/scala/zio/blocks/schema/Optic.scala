package zio.blocks.schema

import zio.blocks.schema.binding._

sealed trait Optic[F[_, _], S, A] { self =>
  def target: Reflect[F, A]

  // Compose this optic with a lens:
  def apply[B](that: Lens[F, A, B]): Optic[F, S, B]

  // Compose this optic with a prism:
  def apply[B](that: Prism[F, A, B]): Optic[F, S, B]

  // Compose this optic with an optional:
  def apply[B](that: Optional[F, A, B]): Optic[F, S, B]

  // Compose this optic with a traversal:
  def apply[B](that: Traversal[F, A, B]): Traversal[F, S, B]

  def refineBinding[G[_, _]](f: RefineBinding[F, G]): Optic[G, S, A]

  def noBinding: Optic[NoBinding, S, A]

  final def list[B](implicit ev: A <:< List[B], F: IsBinding[F]): Traversal[F, S, B] = {
    import Reflect.Extractors.List

    val list = self.asInstanceOf[Optic[F, S, List[B]]]

    list.target match {
      case List(element) => list(Traversal.list(element))

      case _ => sys.error("FIXME - Not a list")
    }
  }

  final def vector[B](implicit ev: A <:< Vector[B], F: IsBinding[F]): Traversal[F, S, B] = {
    import Reflect.Extractors.Vector

    val vector = self.asInstanceOf[Optic[F, S, Vector[B]]]

    vector.target match {
      case Vector(element) => vector(Traversal.vector(element))

      case _ => sys.error("FIXME - Not a vector")
    }
  }

  final def set[B](implicit ev: A <:< Set[B], F: IsBinding[F]): Traversal[F, S, B] = {
    import Reflect.Extractors.Set

    val set = self.asInstanceOf[Optic[F, S, Set[B]]]

    set.target match {
      case Set(element) => set(Traversal.set(element))

      case _ => sys.error("FIXME - Not a set")
    }
  }

  final def array[B](implicit ev: A <:< Array[B], F: IsBinding[F]): Traversal[F, S, B] = {
    import Reflect.Extractors.Array

    val array = self.asInstanceOf[Optic[F, S, Array[B]]]

    array.target match {
      case Array(element) => array(Traversal.array(element))

      case _ => sys.error("FIXME - Not an array")
    }
  }
}

sealed trait Lens[F[_, _], S, A] extends Optic[F, S, A] {
  def get(s: S)(implicit d: HasDeconstructor[F]): A

  // FIXME: Introduce modify(s: S, f: A => A) for performance reasons, implement set in terms of that.
  def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F]): S

  // Compose this lens with a lens:
  override def apply[B](that: Lens[F, A, B]): Lens[F, S, B] = Lens.LensLens(this, that)

  // Compose this lens with a prism:
  override def apply[B](that: Prism[F, A, B]): Optional[F, S, B] = Optional.LensPrism(this, that)

  // Compose this lens with an optional:
  override def apply[B](that: Optional[F, A, B]): Optional[F, S, B] = Optional.LensOptional(this, that)

  // Compose this lens with a traversal:
  override def apply[B](that: Traversal[F, A, B]): Traversal[F, S, B] = Traversal.LensTraversal(this, that)

  override def refineBinding[G[_, _]](f: RefineBinding[F, G]): Lens[G, S, A]

  override def noBinding: Lens[NoBinding, S, A] = refineBinding(RefineBinding.noBinding())
}
object Lens {
  type Bound[S, A] = Lens[Binding, S, A]

  def apply[F[_, _], S, A](parent: Reflect.Record[F, S], child: Term[F, S, A]): Lens[F, S, A] = Root(parent, child)

  final case class Root[F[_, _], S, A](parent: Reflect.Record[F, S], child: Term[F, S, A]) extends Lens[F, S, A] {
    def target: Reflect[F, A] = child.value

    private val register: Register[A] =
      parent.registers(parent.fields.indexWhere(_.name == child.name)).asInstanceOf[Register[A]]

    def get(s: S)(implicit d: HasDeconstructor[F]): A = {
      val registers = Registers()

      d.deconstructor(parent.binding).deconstruct(registers, RegisterOffset.Zero, s)

      register.get(registers, RegisterOffset.Zero)
    }

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F]): S = {
      val registers = Registers()

      d.deconstructor(parent.binding).deconstruct(registers, RegisterOffset.Zero, s)

      register.set(registers, RegisterOffset.Zero, a)

      c.constructor(parent.binding).construct(registers, RegisterOffset.Zero)
    }

    override def refineBinding[G[_, _]](f: RefineBinding[F, G]): Root[G, S, A] =
      Root(parent.refineBinding(f), child.refineBinding(f))

    override def noBinding: Root[NoBinding, S, A] = refineBinding(RefineBinding.noBinding())
  }
  final case class LensLens[F[_, _], S, T, A](first: Lens[F, S, T], second: Lens[F, T, A]) extends Lens[F, S, A] {
    def target: Reflect[F, A] = second.target

    def get(s: S)(implicit d: HasDeconstructor[F]): A = second.get(first.get(s))

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F]): S =
      first.set(s, second.set(first.get(s), a))

    override def refineBinding[G[_, _]](f: RefineBinding[F, G]): LensLens[G, S, T, A] =
      LensLens(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: LensLens[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
}

sealed trait Prism[F[_, _], S, A] extends Optic[F, S, A] {
  def getOption(s: S)(implicit m: HasMatchers[F]): Option[A]

  def reverseGet(a: A): S

  // Compose this prism with a prism:
  override def apply[B](that: Prism[F, A, B]): Prism[F, S, B] = Prism.PrismPrism(this, that)

  // Compose this prism with a lens:
  override def apply[B](that: Lens[F, A, B]): Optional[F, S, B] = Optional.PrismLens(this, that)

  // Compose this prism with an optional:
  override def apply[B](that: Optional[F, A, B]): Optional[F, S, B] = Optional.PrismOptional(this, that)

  // Compose this prism with a traversal:
  override def apply[B](that: Traversal[F, A, B]): Traversal[F, S, B] = Traversal.PrismTraversal(this, that)

  override def refineBinding[G[_, _]](f: RefineBinding[F, G]): Prism[G, S, A]

  override def noBinding: Prism[NoBinding, S, A]
}
object Prism {
  type Bound[S, A] = Prism[Binding, S, A]

  def apply[F[_, _], S, A <: S](parent: Reflect.Variant[F, S], child: Term[F, S, A]): Prism[F, S, A] =
    Root(parent, child)

  final case class Root[F[_, _], S, A <: S](parent: Reflect.Variant[F, S], child: Term[F, S, A])
      extends Prism[F, S, A] {
    private var matcher: Matcher[A] = null

    private def init(m: HasMatchers[F]): Unit =
      if (matcher eq null) {
        val matchers = m.matchers(parent.variantBinding)

        matcher = matchers(parent.cases.indexWhere(_.name == child.name)).asInstanceOf[Matcher[A]]
      }

    def target: Reflect[F, A] = child.value

    def getOption(s: S)(implicit m: HasMatchers[F]): Option[A] = {
      init(m)

      matcher.downcastOption(s)
    }

    def reverseGet(a: A): S = a

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): Root[G, S, A] =
      Root(parent.refineBinding(f), child.refineBinding(f))

    override def noBinding: Root[NoBinding, S, A] = refineBinding(RefineBinding.noBinding())
  }
  final case class PrismPrism[F[_, _], S, T, A](first: Prism[F, S, T], second: Prism[F, T, A]) extends Prism[F, S, A] {
    def target: Reflect[F, A] = second.target

    def getOption(s: S)(implicit m: HasMatchers[F]): Option[A] = first.getOption(s).flatMap(second.getOption)

    def reverseGet(a: A): S = first.reverseGet(second.reverseGet(a))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): PrismPrism[G, S, T, A] =
      PrismPrism(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: PrismPrism[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
}

sealed trait Optional[F[_, _], S, A] extends Optic[F, S, A] {
  def getOption(s: S)(implicit d: HasDeconstructor[F], m: HasMatchers[F]): Option[A]

  def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F], m: HasMatchers[F]): S

  // Compose this optional with a lens:
  override def apply[B](that: Lens[F, A, B]): Optional[F, S, B] = Optional.OptionalLens(this, that)

  // Compose this optional with a prism:
  override def apply[B](that: Prism[F, A, B]): Optional[F, S, B] = Optional.OptionalPrism(this, that)

  // Compose this optional with an optional:
  override def apply[B](that: Optional[F, A, B]): Optional[F, S, B] = Optional.OptionalOptional(this, that)

  // Compose this optional with a traversal:
  override def apply[B](that: Traversal[F, A, B]): Traversal[F, S, B] = Traversal.OptionalTraversal(this, that)

  override def refineBinding[G[_, _]](f: RefineBinding[F, G]): Optional[G, S, A]

  override def noBinding: Optional[NoBinding, S, A]
}
object Optional {
  type Bound[S, A] = Optional[Binding, S, A]

  final case class LensPrism[F[_, _], S, T, A](first: Lens[F, S, T], second: Prism[F, T, A]) extends Optional[F, S, A] {
    def target: Reflect[F, A] = second.target

    def getOption(s: S)(implicit d: HasDeconstructor[F], m: HasMatchers[F]): Option[A] = second.getOption(first.get(s))

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F], m: HasMatchers[F]): S =
      first.set(s, second.reverseGet(a))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): LensPrism[G, S, T, A] =
      LensPrism(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: LensPrism[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
  final case class LensOptional[F[_, _], S, T, A](first: Lens[F, S, T], second: Optional[F, T, A])
      extends Optional[F, S, A] {
    def target: Reflect[F, A] = second.target

    def getOption(s: S)(implicit d: HasDeconstructor[F], m: HasMatchers[F]): Option[A] = second.getOption(first.get(s))

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F], m: HasMatchers[F]): S =
      first.set(s, second.set(first.get(s), a))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): LensOptional[G, S, T, A] =
      LensOptional(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: LensOptional[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
  final case class PrismLens[F[_, _], S, T, A](first: Prism[F, S, T], second: Lens[F, T, A]) extends Optional[F, S, A] {
    def target: Reflect[F, A] = second.target

    def getOption(s: S)(implicit d: HasDeconstructor[F], m: HasMatchers[F]): Option[A] =
      first.getOption(s).map(second.get)

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F], m: HasMatchers[F]): S =
      first.reverseGet(second.set(first.getOption(s).get, a))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): PrismLens[G, S, T, A] =
      PrismLens(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: PrismLens[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
  final case class PrismOptional[F[_, _], S, T, A](
    first: Prism[F, S, T],
    second: Optional[F, T, A]
  ) extends Optional[F, S, A] {
    def target: Reflect[F, A] = second.target

    def getOption(s: S)(implicit d: HasDeconstructor[F], m: HasMatchers[F]): Option[A] =
      first.getOption(s).flatMap(second.getOption)

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F], m: HasMatchers[F]): S =
      first.reverseGet(second.set(first.getOption(s).get, a))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): PrismOptional[G, S, T, A] =
      PrismOptional(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: PrismOptional[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
  final case class OptionalLens[F[_, _], S, T, A](first: Optional[F, S, T], second: Lens[F, T, A])
      extends Optional[F, S, A] {
    def target: Reflect[F, A] = second.target

    def getOption(s: S)(implicit d: HasDeconstructor[F], m: HasMatchers[F]): Option[A] =
      first.getOption(s).map(second.get)

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F], m: HasMatchers[F]): S =
      first.set(s, second.set(first.getOption(s).get, a))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): OptionalLens[G, S, T, A] =
      OptionalLens(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: OptionalLens[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
  final case class OptionalPrism[F[_, _], S, T, A](first: Optional[F, S, T], second: Prism[F, T, A])
      extends Optional[F, S, A] {
    def target: Reflect[F, A] = second.target

    def getOption(s: S)(implicit d: HasDeconstructor[F], m: HasMatchers[F]): Option[A] =
      first.getOption(s).flatMap(second.getOption)

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F], m: HasMatchers[F]): S =
      first.set(s, second.reverseGet(a))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): OptionalPrism[G, S, T, A] =
      OptionalPrism(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: OptionalPrism[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
  final case class OptionalOptional[F[_, _], S, T, A](first: Optional[F, S, T], second: Optional[F, T, A])
      extends Optional[F, S, A] {
    def target: Reflect[F, A] = second.target

    def getOption(s: S)(implicit d: HasDeconstructor[F], m: HasMatchers[F]): Option[A] =
      first.getOption(s).flatMap(second.getOption)

    def set(s: S, a: A)(implicit d: HasDeconstructor[F], c: HasConstructor[F], m: HasMatchers[F]): S =
      first.set(s, second.set(first.getOption(s).get, a))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): OptionalOptional[G, S, T, A] =
      OptionalOptional(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: OptionalOptional[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
}

sealed trait Traversal[F[_, _], S, A] extends Optic[F, S, A] { self =>
  def target: Reflect[F, A]

  def fold[Z](s: S)(zero: Z, f: (Z, A) => Z)(implicit
    d: HasDeconstructor[F],
    m: HasMatchers[F],
    sd: HasSeqDeconstructor[F],
    md: HasMapDeconstructor[F]
  ): Z

  // Core operation - modify all focuses
  def modify(s: S, f: A => A)(implicit
    d: HasDeconstructor[F],
    c: HasConstructor[F],
    m: HasMatchers[F],
    sd: HasSeqDeconstructor[F],
    sc: HasSeqConstructor[F],
    md: HasMapDeconstructor[F],
    mc: HasMapConstructor[F]
  ): S

  // Compose this traversal with a lens:
  override def apply[B](that: Lens[F, A, B]): Traversal[F, S, B] = Traversal.TraversalLens(this, that)

  // Compose this traversal with a prism:
  override def apply[B](that: Prism[F, A, B]): Traversal[F, S, B] = Traversal.TraversalPrism(this, that)

  // Compose this traversal with an optional:
  override def apply[B](that: Optional[F, A, B]): Traversal[F, S, B] = Traversal.TraversalOptional(this, that)

  // Compose this traversal with a traversal:
  override def apply[B](that: Traversal[F, A, B]): Traversal[F, S, B] = Traversal.TraversalTraversal(this, that)

  override def refineBinding[G[_, _]](f: RefineBinding[F, G]): Traversal[G, S, A]

  override def noBinding: Traversal[NoBinding, S, A]
}

object Traversal {
  type Bound[S, A] = Traversal[Binding, S, A]

  def apply[F[_, _], A, C[_]](parent: Reflect.Sequence[F, A, C]): Traversal[F, C[A], A] = Seq(parent)

  def list[F[_, _], A](reflect: Reflect[F, A])(implicit F: IsBinding[F]): Traversal[F, List[A], A] = Traversal(
    Reflect.list(reflect)
  )

  def set[F[_, _], A](reflect: Reflect[F, A])(implicit F: IsBinding[F]): Traversal[F, Set[A], A] = Traversal(
    Reflect.set(reflect)
  )

  def vector[F[_, _], A](reflect: Reflect[F, A])(implicit F: IsBinding[F]): Traversal[F, Vector[A], A] = Traversal(
    Reflect.vector(reflect)
  )

  def array[F[_, _], A](reflect: Reflect[F, A])(implicit F: IsBinding[F]): Traversal[F, Array[A], A] = Traversal(
    Reflect.array(reflect)
  )

  final case class Seq[F[_, _], A, C[_]](seq: Reflect.Sequence[F, A, C]) extends Traversal[F, C[A], A] {
    def target: Reflect[F, A] = seq.element

    def fold[Z](s: C[A])(
      zero: Z,
      f: (Z, A) => Z
    )(implicit d: HasDeconstructor[F], m: HasMatchers[F], sd: HasSeqDeconstructor[F], md: HasMapDeconstructor[F]): Z = {
      val deconstructor = sd.deconstructor(seq.binding)

      deconstructor match {
        case indexed: SeqDeconstructor.Indexed[c] =>
          var idx = 0
          val len = indexed.length(s)
          var z   = zero

          while (idx < len) {
            z = f(z, indexed.objectAt(s, idx)) // TODO: Specialize

            idx = idx + 1
          }

          z

        case _ =>
          var z  = zero
          val it = deconstructor.deconstruct(s)

          while (it.hasNext) {
            z = f(z, it.next())
          }

          z
      }
    }

    def modify(s: C[A], f: A => A)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): C[A] = {
      val deconstructor = sd.deconstructor(seq.binding)

      deconstructor match {
        case indexed: SeqDeconstructor.Indexed[c] =>
          val len         = indexed.length(s)
          val constructor = sc.constructor(seq.binding)
          val builder     = constructor.newObjectBuilder[A]() // TODO: Specialize

          var idx = 0

          while (idx < len) {
            constructor.addObject(builder, f(indexed.objectAt(s, idx))) // TODO: Specialize

            idx = idx + 1
          }

          constructor.resultObject(builder)

        case _ =>
          val constructor = sc.constructor(seq.binding)
          val builder     = constructor.newObjectBuilder[A]() // TODO: Specialize
          val it          = deconstructor.deconstruct(s)

          while (it.hasNext) {
            constructor.addObject(builder, f(it.next()))
          }

          constructor.resultObject(builder)
      }
    }

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): Seq[G, A, C] = Seq(seq.refineBinding(f))

    override def noBinding: Seq[NoBinding, A, C] = refineBinding(RefineBinding.noBinding())
  }

  final case class MapKeys[F[_, _], Key, Value, M[_, _]](map: Reflect.Map[F, Key, Value, M])
      extends Traversal[F, M[Key, Value], Key] {
    def target: Reflect[F, Key] = map.key

    def fold[Z](s: M[Key, Value])(
      zero: Z,
      f: (Z, Key) => Z
    )(implicit d: HasDeconstructor[F], m: HasMatchers[F], sd: HasSeqDeconstructor[F], md: HasMapDeconstructor[F]): Z = {
      val deconstructor = md.deconstructor(map.binding)

      var z  = zero
      val it = deconstructor.deconstruct(s)

      while (it.hasNext) {
        val next = it.next()
        z = f(z, deconstructor.getKey(next))
      }

      z
    }

    def modify(s: M[Key, Value], f: Key => Key)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): M[Key, Value] = {
      val deconstructor = md.deconstructor(map.binding)
      val constructor   = mc.constructor(map.binding)
      val builder       = constructor.newObjectBuilder[Key, Value]()

      val it = deconstructor.deconstruct(s)

      while (it.hasNext) {
        val next  = it.next()
        val key   = deconstructor.getKey(next)
        val value = deconstructor.getValue(next)

        constructor.addObject(builder, f(key), value)
      }

      constructor.resultObject(builder)
    }

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): MapKeys[G, Key, Value, M] = MapKeys(map.refineBinding(f))

    override def noBinding: MapKeys[NoBinding, Key, Value, M] = refineBinding(RefineBinding.noBinding())
  }

  final case class MapValues[F[_, _], Key, Value, M[_, _]](map: Reflect.Map[F, Key, Value, M])
      extends Traversal[F, M[Key, Value], Value] {
    def target: Reflect[F, Value] = map.value

    def fold[Z](s: M[Key, Value])(
      zero: Z,
      f: (Z, Value) => Z
    )(implicit d: HasDeconstructor[F], m: HasMatchers[F], sd: HasSeqDeconstructor[F], md: HasMapDeconstructor[F]): Z = {
      val deconstructor = md.deconstructor(map.binding)

      var z  = zero
      val it = deconstructor.deconstruct(s)

      while (it.hasNext) {
        val next = it.next()
        z = f(z, deconstructor.getValue(next))
      }

      z
    }

    def modify(s: M[Key, Value], f: Value => Value)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): M[Key, Value] = {
      val deconstructor = md.deconstructor(map.binding)
      val constructor   = mc.constructor(map.binding)
      val builder       = constructor.newObjectBuilder[Key, Value]()

      val it = deconstructor.deconstruct(s)

      while (it.hasNext) {
        val next  = it.next()
        val key   = deconstructor.getKey(next)
        val value = deconstructor.getValue(next)

        constructor.addObject(builder, key, f(value))
      }

      constructor.resultObject(builder)
    }

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): MapValues[G, Key, Value, M] = MapValues(map.refineBinding(f))

    override def noBinding: MapValues[NoBinding, Key, Value, M] = refineBinding(RefineBinding.noBinding())
  }

  // All compositions that yield Traversal:
  final case class TraversalTraversal[F[_, _], S, T, A](
    first: Traversal[F, S, T],
    second: Traversal[F, T, A]
  ) extends Traversal[F, S, A] {
    def target: Reflect[F, A] = second.target

    def fold[Z](s: S)(zero: Z, f: (Z, A) => Z)(implicit
      d: HasDeconstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      md: HasMapDeconstructor[F]
    ): Z =
      first.fold[Z](s)(zero, (z, t) => second.fold(t)(z, f))

    def modify(s: S, f: A => A)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): S =
      first.modify(s, t => second.modify(t, f))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): TraversalTraversal[G, S, T, A] =
      TraversalTraversal(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: TraversalTraversal[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }

  final case class TraversalLens[F[_, _], S, T, A](
    first: Traversal[F, S, T],
    second: Lens[F, T, A]
  ) extends Traversal[F, S, A] {
    def target: Reflect[F, A] = second.target

    def fold[Z](s: S)(zero: Z, f: (Z, A) => Z)(implicit
      d: HasDeconstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      md: HasMapDeconstructor[F]
    ): Z =
      first.fold(s)(zero, (z, t) => f(z, second.get(t)))

    def modify(s: S, f: A => A)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): S =
      first.modify(s, t => second.set(t, f(second.get(t))))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): TraversalLens[G, S, T, A] =
      TraversalLens(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: TraversalLens[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }

  final case class TraversalPrism[F[_, _], S, T, A](
    first: Traversal[F, S, T],
    second: Prism[F, T, A]
  ) extends Traversal[F, S, A] {
    def target: Reflect[F, A] = second.target

    def fold[Z](s: S)(zero: Z, f: (Z, A) => Z)(implicit
      d: HasDeconstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      md: HasMapDeconstructor[F]
    ): Z =
      first.fold[Z](s)(zero, (z, t) => second.getOption(t).map(a => f(z, a)).getOrElse(z))

    def modify(s: S, f: A => A)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): S =
      first.modify(s, t => second.getOption(t).map(a => second.reverseGet(f(a))).getOrElse(t))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): TraversalPrism[G, S, T, A] =
      TraversalPrism(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: TraversalPrism[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }

  final case class TraversalOptional[F[_, _], S, T, A](
    first: Traversal[F, S, T],
    second: Optional[F, T, A]
  ) extends Traversal[F, S, A] {
    def target: Reflect[F, A] = second.target

    def fold[Z](s: S)(zero: Z, f: (Z, A) => Z)(implicit
      d: HasDeconstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      md: HasMapDeconstructor[F]
    ): Z =
      first.fold[Z](s)(zero, (z, t) => second.getOption(t).map(a => f(z, a)).getOrElse(z))

    def modify(s: S, f: A => A)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): S =
      first.modify(s, t => second.getOption(t).map(a => second.set(t, f(a))).getOrElse(t))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): TraversalOptional[G, S, T, A] =
      TraversalOptional(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: TraversalOptional[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }

  final case class LensTraversal[F[_, _], S, T, A](
    first: Lens[F, S, T],
    second: Traversal[F, T, A]
  ) extends Traversal[F, S, A] {
    def target: Reflect[F, A] = second.target

    def fold[Z](s: S)(zero: Z, f: (Z, A) => Z)(implicit
      d: HasDeconstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      md: HasMapDeconstructor[F]
    ): Z =
      second.fold(first.get(s))(zero, f)

    def modify(s: S, f: A => A)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): S =
      first.set(s, second.modify(first.get(s), f))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): LensTraversal[G, S, T, A] =
      LensTraversal(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: LensTraversal[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }

  final case class PrismTraversal[F[_, _], S, T, A](
    first: Prism[F, S, T],
    second: Traversal[F, T, A]
  ) extends Traversal[F, S, A] {
    def target: Reflect[F, A] = second.target

    def fold[Z](s: S)(zero: Z, f: (Z, A) => Z)(implicit
      d: HasDeconstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      md: HasMapDeconstructor[F]
    ): Z =
      first.getOption(s).map(t => second.fold(t)(zero, f)).getOrElse(zero)

    def modify(s: S, f: A => A)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): S =
      first.getOption(s).map(t => first.reverseGet(second.modify(t, f))).getOrElse(s)

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): PrismTraversal[G, S, T, A] =
      PrismTraversal(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: PrismTraversal[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }

  final case class OptionalTraversal[F[_, _], S, T, A](
    first: Optional[F, S, T],
    second: Traversal[F, T, A]
  ) extends Traversal[F, S, A] {
    def target: Reflect[F, A] = second.target

    def fold[Z](s: S)(zero: Z, f: (Z, A) => Z)(implicit
      d: HasDeconstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      md: HasMapDeconstructor[F]
    ): Z =
      first.getOption(s).map(t => second.fold(t)(zero, f)).getOrElse(zero)

    def modify(s: S, f: A => A)(implicit
      d: HasDeconstructor[F],
      c: HasConstructor[F],
      m: HasMatchers[F],
      sd: HasSeqDeconstructor[F],
      sc: HasSeqConstructor[F],
      md: HasMapDeconstructor[F],
      mc: HasMapConstructor[F]
    ): S =
      first.getOption(s).map(t => first.set(s, second.modify(t, f))).getOrElse(s)

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): OptionalTraversal[G, S, T, A] =
      OptionalTraversal(first.refineBinding(f), second.refineBinding(f))

    override def noBinding: OptionalTraversal[NoBinding, S, T, A] = refineBinding(RefineBinding.noBinding())
  }
}
