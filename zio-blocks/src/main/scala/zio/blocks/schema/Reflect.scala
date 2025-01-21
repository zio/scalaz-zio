package zio.blocks.schema

import zio.blocks.schema.binding._

import RegisterOffset.RegisterOffset

sealed trait Reflect[+F[_, _], A] extends Reflectable[A] { self =>
  protected def inner: Any

  def refineBinding[G[_, _]](f: RefineBinding[F, G]): Reflect[G, A]

  def noBinding: Reflect[NoBinding, A] = refineBinding(RefineBinding.noBinding())

  def asTerm[S](name: String): Term[F, S, A] = Term(name, this, Doc.Empty, List.empty)

  override def hashCode: Int = inner.hashCode

  override def equals(obj: Any): Boolean = obj match {
    case that: Reflect[_, _] => inner == that.inner
    case _                   => false
  }
}
object Reflect {
  type Bound[A] = Reflect[Binding, A]

  final case class Record[F[_, _], A](
    fields: List[Term[F, A, ?]],
    typeName: TypeName[A],
    binding: F[BindingType.Record, A],
    doc: Doc,
    anns: List[Modifier.Record]
  ) extends Reflect[F, A] { self =>
    protected def inner: Any = (fields, typeName, doc, anns)

    def fieldByName(name: String): Option[Term[F, A, ?]] = fields.find(_.name == name)

    def lensByIndex(index: Int): Lens[F, A, ?] = Lens(self, fields(index))

    def lensByName(name: String): Option[Lens[F, A, ?]] = fieldByName(name).map(Lens(self, _))

    val length: Int = fields.length

    def registerByName(name: String): Option[Register[?]] =
      Some(fields.indexWhere(_.name == name)).filter(_ >= 0).map(registers)

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): Record[G, A] =
      Record(fields.map(_.refineBinding(f)), typeName, f(binding), doc, anns)

    val registers: IndexedSeq[Register[?]] =
      fields
        .foldLeft(List.empty[Register[?]] -> RegisterOffset.Zero) {
          case ((list, registerOffset), Term(_, Reflect.Primitive(primType, _, _), _, _)) =>
            primType match {
              case PrimitiveType.Unit =>
                (Register.None :: list, registerOffset)

              case PrimitiveType.Boolean =>
                val index = RegisterOffset.getBooleans(registerOffset)

                (Register.Boolean(index) :: list, RegisterOffset.incrementBooleans(registerOffset))

              case PrimitiveType.Byte =>
                val index = RegisterOffset.getBytes(registerOffset)

                (Register.Byte(index) :: list, RegisterOffset.incrementBytes(registerOffset))

              case PrimitiveType.Short(_) =>
                val index = RegisterOffset.getShorts(registerOffset)

                (Register.Short(index) :: list, RegisterOffset.incrementShorts(registerOffset))

              case PrimitiveType.Int(_) =>
                val index = RegisterOffset.getInts(registerOffset)

                (Register.Int(index) :: list, RegisterOffset.incrementInts(registerOffset))

              case PrimitiveType.Long(_) =>
                val index = RegisterOffset.getLongs(registerOffset)

                (Register.Long(index) :: list, RegisterOffset.incrementLongs(registerOffset))

              case PrimitiveType.Float(_) =>
                val index = RegisterOffset.getFloats(registerOffset)

                (Register.Float(index) :: list, RegisterOffset.incrementFloats(registerOffset))

              case PrimitiveType.Double(_) =>
                val index = RegisterOffset.getDoubles(registerOffset)

                (Register.Double(index) :: list, RegisterOffset.incrementDoubles(registerOffset))

              case PrimitiveType.Char(_) =>
                val index = RegisterOffset.getChars(registerOffset)

                (Register.Char(index) :: list, RegisterOffset.incrementChars(registerOffset))

              case PrimitiveType.String(_) =>
                val index = RegisterOffset.getObjects(registerOffset)

                (Register.Object(index) :: list, RegisterOffset.incrementObjects(registerOffset))
            }

          case ((list, registerOffset), _) =>
            val index = RegisterOffset.getObjects(registerOffset)

            (Register.Object(index) :: list, RegisterOffset.incrementObjects(registerOffset))
        }
        ._1
        .toArray
        .reverse
        .toIndexedSeq

    val size: RegisterOffset = registers.foldLeft(RegisterOffset.Zero) { case (acc, register) =>
      RegisterOffset.add(acc, register.size)
    }
  }
  object Record {
    type Bound[A] = Record[Binding, A]
  }
  final case class Variant[F[_, _], A](
    cases: List[Term[F, A, ? <: A]],
    typeName: TypeName[A],
    variantBinding: F[BindingType.Variant, A],
    doc: Doc,
    anns: List[Modifier.Variant],
    defaultCase: Option[A]
  ) extends Reflect[F, A] {
    protected def inner: Any = (cases, typeName, doc, anns, defaultCase)

    def caseByName(name: String): Option[Term[F, A, ? <: A]] = cases.find(_.name == name)

    def prismByIndex(index: Int): Prism[F, A, ? <: A] = Prism(this, cases(index))

    def prismByName(name: String): Option[Prism[F, A, ? <: A]] = caseByName(name).map(Prism(this, _))

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): Variant[G, A] =
      Variant(cases.map(_.refineBinding(f)), typeName, f(variantBinding), doc, anns, defaultCase)
  }
  object Variant {
    type Bound[A] = Variant[Binding, A]
  }
  final case class Sequence[F[_, _], A, C[_]](
    element: Reflect[F, A],
    binding: F[BindingType.Seq[C], C[A]],
    typeName: TypeName[C[A]],
    doc: Doc
  ) extends Reflect[F, C[A]] {
    protected def inner: Any = (element, typeName, doc)

    def anns: List[Nothing] = List.empty

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): Sequence[G, A, C] =
      Sequence(element.refineBinding(f), f(binding), typeName, doc)

    def traversal: Traversal[F, C[A], A] = Traversal(this)
  }
  object Sequence {
    type Bound[A, C[_]] = Sequence[Binding, A, C]
  }
  final case class Map[F[_, _], Key, Value, M[_, _]](
    key: Reflect[F, Key],
    value: Reflect[F, Value],
    binding: F[BindingType.Map[M], M[Key, Value]],
    typeName: TypeName[M[Key, Value]],
    doc: Doc
  ) extends Reflect[F, M[Key, Value]] {
    protected def inner: Any = (key, value, typeName, doc)

    def anns: List[Nothing] = List.empty

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): Map[G, Key, Value, M] =
      Map(key.refineBinding(f), value.refineBinding(f), f(binding), typeName, doc)

    def keys: Traversal[F, M[Key, Value], Key] = Traversal.MapKeys(this)

    def values: Traversal[F, M[Key, Value], Value] = Traversal.MapValues(this)
  }
  object Map {
    type Bound[K, V, M[_, _]] = Map[Binding, K, V, M]
  }
  final case class Dynamic(anns: List[Modifier.Dynamic], doc: Doc) extends Reflect[Binding.Unused, DynamicValue] {
    protected def inner: Any = (anns, doc)

    def refineBinding[G[_, _]](f: RefineBinding[Binding.Unused, G]): Reflect[G, DynamicValue] = this
  }
  final case class Primitive[A](primitiveType: PrimitiveType[A], typeName: TypeName[A], doc: Doc)
      extends Reflect[Binding.Unused, A] { self =>
    protected def inner: Any = (primitiveType, typeName, doc)

    def anns: List[Nothing] = List.empty

    def refineBinding[G[_, _]](f: RefineBinding[Binding.Unused, G]): Primitive[A] = self
  }
  final case class Deferred[F[_, _], A](_value: () => Reflect[F, A]) extends Reflect[F, A] {
    protected def inner: Any = value.inner

    lazy val value = _value()

    def anns: List[Modifier] = value.anns

    def doc: Doc = value.doc

    def refineBinding[G[_, _]](f: RefineBinding[F, G]): Reflect[G, A] = value.refineBinding(f)
  }

  val unit: Reflect[Binding.Unused, Unit] =
    Primitive(PrimitiveType.Unit, TypeName.unit, Doc.Empty)

  val boolean: Reflect[Binding.Unused, Boolean] =
    Primitive(PrimitiveType.Boolean, TypeName.boolean, Doc.Empty)

  val byte: Reflect[Binding.Unused, Byte] =
    Primitive(PrimitiveType.Byte, TypeName.byte, Doc.Empty)

  val short: Reflect[Binding.Unused, Short] =
    Primitive(
      PrimitiveType.Short(Validation.None),
      TypeName.short,
      Doc.Empty
    )

  val int: Reflect[Binding.Unused, Int] =
    Primitive(
      PrimitiveType.Int(Validation.None),
      TypeName.int,
      Doc.Empty
    )

  val long: Reflect[Binding.Unused, Long] =
    Primitive(
      PrimitiveType.Long(Validation.None),
      TypeName.long,
      Doc.Empty
    )

  val float: Reflect[Binding.Unused, Float] =
    Primitive(
      PrimitiveType.Float(Validation.None),
      TypeName.float,
      Doc.Empty
    )

  val double: Reflect[Binding.Unused, Double] =
    Primitive(
      PrimitiveType.Double(Validation.None),
      TypeName.double,
      Doc.Empty
    )

  val char: Reflect[Binding.Unused, Char] =
    Primitive(
      PrimitiveType.Char(Validation.None),
      TypeName.char,
      Doc.Empty
    )

  val string: Reflect[Binding.Unused, String] =
    Primitive(
      PrimitiveType.String(Validation.None),
      TypeName.string,
      Doc.Empty
    )

  def set[F[_, _], A](element: Reflect[F, A])(implicit F: IsBinding[F]): Sequence[F, A, Set] =
    (Sequence(element, F.unapply(Binding.Seq.set), TypeName.set[A], Doc.Empty))

  def list[F[_, _], A](element: Reflect[F, A])(implicit F: IsBinding[F]): Sequence[F, A, List] =
    (Sequence(element, F.unapply(Binding.Seq.list), TypeName.list[A], Doc.Empty))

  def vector[F[_, _], A](element: Reflect[F, A])(implicit F: IsBinding[F]): Sequence[F, A, Vector] =
    (Sequence(element, F.unapply(Binding.Seq.vector), TypeName.vector[A], Doc.Empty))

  def array[F[_, _], A](element: Reflect[F, A])(implicit F: IsBinding[F]): Sequence[F, A, Array] =
    (Sequence(element, F.unapply(Binding.Seq.array), TypeName.array[A], Doc.Empty))

  def some[F[_, _], A](element: Reflect[F, A])(implicit F: IsBinding[F]): Record[F, Some[A]] =
    Record(
      List(Term("value", element, Doc.Empty, List.empty)),
      TypeName.some[A],
      F.unapply(Binding.Record.some[A]),
      Doc.Empty,
      List.empty
    )

  def none[F[_, _]](implicit F: IsBinding[F]): Record[F, None.type] =
    Record(
      List.empty,
      TypeName.none,
      F.unapply(Binding.Record.none),
      Doc.Empty,
      List.empty
    )

  def option[F[_, _], A](element: Reflect[F, A])(implicit F: IsBinding[F]): Variant[F, Option[A]] = {
    val noneTerm: Term[F, Option[A], None.type] = Term("None", none, Doc.Empty, List.empty)

    val someTerm: Term[F, Option[A], Some[A]] = Term("Some", some[F, A](element), Doc.Empty, List.empty)

    Variant(
      List(noneTerm, someTerm),
      TypeName.option[A],
      F.unapply(Binding.Variant.option[A]),
      Doc.Empty,
      List.empty,
      None
    )
  }

  def left[F[_, _], A, B](element: Reflect[F, A])(implicit F: IsBinding[F]): Record[F, Left[A, B]] =
    Record(
      List(Term("value", element, Doc.Empty, List.empty)),
      TypeName.left[A, B],
      F.unapply(Binding.Record.left[A, B]),
      Doc.Empty,
      List.empty
    )

  def right[F[_, _], A, B](element: Reflect[F, B])(implicit F: IsBinding[F]): Record[F, Right[A, B]] =
    Record(
      List(Term("value", element, Doc.Empty, List.empty)),
      TypeName.right[A, B],
      F.unapply(Binding.Record.right[A, B]),
      Doc.Empty,
      List.empty
    )

  def either[F[_, _], L, R](l: Reflect[F, L], r: Reflect[F, R])(implicit F: IsBinding[F]): Variant[F, Either[L, R]] = {
    val leftTerm: Term[F, Either[L, R], Left[L, R]] = Term("Left", left(l), Doc.Empty, List.empty)

    val rightTerm: Term[F, Either[L, R], Right[L, R]] = Term("Right", right(r), Doc.Empty, List.empty)

    Variant(
      List(leftTerm, rightTerm),
      TypeName.either[L, R],
      F.unapply(Binding.Variant.either[L, R]),
      Doc.Empty,
      List.empty,
      None
    )
  }

  def tuple2[F[_, _], A, B](_1: Reflect[F, A], _2: Reflect[F, B])(implicit F: IsBinding[F]): Record[F, (A, B)] =
    Record(
      List(Term("_1", _1, Doc.Empty, List.empty), Term("_2", _2, Doc.Empty, List.empty)),
      TypeName.tuple2[A, B],
      F.unapply(Binding.Record.tuple2[A, B]),
      Doc.Empty,
      List.empty
    )

  def tuple3[F[_, _], A, B, C](_1: Reflect[F, A], _2: Reflect[F, B], _3: Reflect[F, C])(implicit F: IsBinding[F]): Record[F, (A, B, C)] =
    Record(
      List(
        Term("_1", _1, Doc.Empty, List.empty),
        Term("_2", _2, Doc.Empty, List.empty),
        Term("_3", _3, Doc.Empty, List.empty)
      ),
      TypeName.tuple3[A, B, C],
      F.unapply(Binding.Record.tuple3[A, B, C]),
      Doc.Empty,
      List.empty
    )

  def tuple4[F[_, _], A, B, C, D](
    _1: Reflect[F, A],
    _2: Reflect[F, B],
    _3: Reflect[F, C],
    _4: Reflect[F, D]
  )(implicit F: IsBinding[F]): Record[F, (A, B, C, D)] =
    Record(
      List(
        Term("_1", _1, Doc.Empty, List.empty),
        Term("_2", _2, Doc.Empty, List.empty),
        Term("_3", _3, Doc.Empty, List.empty),
        Term("_4", _4, Doc.Empty, List.empty)
      ),
      TypeName.tuple4[A, B, C, D],
      F.unapply(Binding.Record.tuple4[A, B, C, D]),
      Doc.Empty,
      List.empty
    )

  def tuple5[F[_, _], A, B, C, D, E](
    _1: Reflect[F, A],
    _2: Reflect[F, B],
    _3: Reflect[F, C],
    _4: Reflect[F, D],
    _5: Reflect[F, E]
  )(implicit F: IsBinding[F]): Record[F, (A, B, C, D, E)] =
    Record(
      List(
        Term("_1", _1, Doc.Empty, List.empty),
        Term("_2", _2, Doc.Empty, List.empty),
        Term("_3", _3, Doc.Empty, List.empty),
        Term("_4", _4, Doc.Empty, List.empty),
        Term("_5", _5, Doc.Empty, List.empty)
      ),
      TypeName.tuple5[A, B, C, D, E],
      F.unapply(Binding.Record.tuple5[A, B, C, D, E]),
      Doc.Empty,
      List.empty
    )

  object Extractors {
    object List {
      def unapply[F[_, _], A](reflect: Reflect[F, List[A]]): Option[Reflect[F, A]] =
        reflect match {
          case Sequence(element, _, tn, _) if tn == TypeName.list => Some(element)
          case _                                                  => None
        }
    }
    object Vector {
      def unapply[F[_, _], A](reflect: Reflect[F, Vector[A]]): Option[Reflect[F, A]] =
        reflect match {
          case Sequence(element, _, tn, _) if tn == TypeName.vector => Some(element)
          case _                                                    => None
        }
    }
    object Set {
      def unapply[F[_, _], A](reflect: Reflect[F, Set[A]]): Option[Reflect[F, A]] =
        reflect match {
          case Sequence(element, _, tn, _) if tn == TypeName.set => Some(element)
          case _                                                 => None
        }
    }    
    object Array {
      def unapply[F[_, _], A](reflect: Reflect[F, Array[A]]): Option[Reflect[F, A]] =
        reflect match {
          case Sequence(element, _, tn, _) if tn == TypeName.array => Some(element)
          case _                                                   => None
        }
    }
    object Option {
      def unapply[F[_, _], A](reflect: Reflect[F, Option[A]]): Option[Reflect[F, A]] =
        reflect match {
          case Variant(noneTerm :: someTerm :: Nil, tn, _, _, _, _) if tn == TypeName.option =>
            someTerm match {
              case Term("Some", element, _, _) => Some(element.asInstanceOf[Reflect[F, A]])
              case _ => None
            }
            
          case _ => None
        }
    }
    object Either {
      def unapply[F[_, _], L, R](reflect: Reflect[F, Either[L, R]]): Option[(Reflect[F, L], Reflect[F, R])] =
        reflect match {
          case Variant(leftTerm :: rightTerm :: Nil, tn, _, _, _, _) if tn == TypeName.either =>
            (leftTerm, rightTerm) match {
              case (Term("Left", left, _, _), Term("Right", right, _, _)) => Some((left.asInstanceOf[Reflect[F, L]], right.asInstanceOf[Reflect[F, R]]))
              case _ => None
            }
            
          case _ => None
        }
    }
  }
}
