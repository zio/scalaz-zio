package zio.blocks.schema.binding

/**
 * A binding is used to attach non-serializable Scala functions, such as
 * constructors, deconstructors, and matchers, to a reflection type.
 *
 * The {{Binding}} type is indexed by `T`, which is a phantom type that
 * represents the type of binding. The type `A` represents the type of the
 * reflection type.
 *
 * So, for example, `Binding[BindingType.Record, Int]` represents a binding for
 * the reflection type `Int` that has a record binding (and therefore, both a
 * constructor and a deconstructor).
 */
sealed trait Binding[T, A]
object Binding {
  type Unused[T, A] = Nothing

  final case class Record[A](constructor: Constructor[A], deconstructor: Deconstructor[A])
      extends Binding[BindingType.Record, A] {
    def transform[B](f: A => B)(g: B => A): Record[B] = Record(constructor.map(f), deconstructor.contramap(g))
  }
  object Record {
    def apply[A](implicit r: Record[A]): Record[A] = r

    def of[A]: Record[A] = _of.asInstanceOf[Record[A]]

    def some[A]: Record[Some[A]] = Record(Constructor.of[A].map(Some(_)), Deconstructor.of[A].contramap(_.value))

    val none: Record[None.type] = Record(Constructor.none, Deconstructor.none)

    val int: Record[Int] = Record(Constructor.int, Deconstructor.int)

    def left[A, B]: Record[Left[A, B]] = _left.asInstanceOf[Record[Left[A, B]]]

    def right[A, B]: Record[Right[A, B]] = _right.asInstanceOf[Record[Right[A, B]]]

    def tuple2[A, B]: Record[(A, B)] = _tuple2.asInstanceOf[Record[(A, B)]]

    def tuple3[A, B, C]: Record[(A, B, C)] = _tuple3.asInstanceOf[Record[(A, B, C)]]

    def tuple4[A, B, C, D]: Record[(A, B, C, D)] = _tuple4.asInstanceOf[Record[(A, B, C, D)]]

    def tuple5[A, B, C, D, E]: Record[(A, B, C, D, E)] = _tuple5.asInstanceOf[Record[(A, B, C, D, E)]]

    private val _of = new Record[AnyRef](Constructor.of[AnyRef], Deconstructor.of[AnyRef])

    private val _left = new Record[Left[AnyRef, AnyRef]](
      Constructor.of[AnyRef].map(Left(_)),
      Deconstructor.of[AnyRef].contramap(_.value)
    )

    private val _right = new Record[Right[AnyRef, AnyRef]](
      Constructor.of[AnyRef].map(Right(_)),
      Deconstructor.of[AnyRef].contramap(_.value)
    )

    private val _tuple2 = new Record[(AnyRef, AnyRef)](
      Constructor.tuple2[AnyRef, AnyRef](Constructor.of[AnyRef], Constructor.of[AnyRef]),
      Deconstructor.tuple2[AnyRef, AnyRef](Deconstructor.of[AnyRef], Deconstructor.of[AnyRef])
    )

    private val _tuple3 = new Record[(AnyRef, AnyRef, AnyRef)](
      Constructor
        .tuple3[AnyRef, AnyRef, AnyRef](Constructor.of[AnyRef], Constructor.of[AnyRef], Constructor.of[AnyRef]),
      Deconstructor
        .tuple3[AnyRef, AnyRef, AnyRef](Deconstructor.of[AnyRef], Deconstructor.of[AnyRef], Deconstructor.of[AnyRef])
    )

    private val _tuple4 = new Record[(AnyRef, AnyRef, AnyRef, AnyRef)](
      Constructor.tuple4[AnyRef, AnyRef, AnyRef, AnyRef](
        Constructor.of[AnyRef],
        Constructor.of[AnyRef],
        Constructor.of[AnyRef],
        Constructor.of[AnyRef]
      ),
      Deconstructor.tuple4[AnyRef, AnyRef, AnyRef, AnyRef](
        Deconstructor.of[AnyRef],
        Deconstructor.of[AnyRef],
        Deconstructor.of[AnyRef],
        Deconstructor.of[AnyRef]
      )
    )

    private val _tuple5 = new Record[(AnyRef, AnyRef, AnyRef, AnyRef, AnyRef)](
      Constructor.tuple5[AnyRef, AnyRef, AnyRef, AnyRef, AnyRef](
        Constructor.of[AnyRef],
        Constructor.of[AnyRef],
        Constructor.of[AnyRef],
        Constructor.of[AnyRef],
        Constructor.of[AnyRef]
      ),
      Deconstructor.tuple5[AnyRef, AnyRef, AnyRef, AnyRef, AnyRef](
        Deconstructor.of[AnyRef],
        Deconstructor.of[AnyRef],
        Deconstructor.of[AnyRef],
        Deconstructor.of[AnyRef],
        Deconstructor.of[AnyRef]
      )
    )
  }
  final case class Variant[A](discriminator: Discriminator[A], matchers: Matchers[A])
      extends Binding[BindingType.Variant, A]
  object Variant {
    def apply[A](implicit v: Variant[A]): Variant[A] = v

    def option[A]: Variant[Option[A]] = Variant(Discriminator.option[A], Matchers(Matcher.some, Matcher.none))

    def either[L, R]: Variant[Either[L, R]] = Variant(Discriminator.either[L, R], Matchers(Matcher.left, Matcher.right))
  }

  final case class Seq[C[_], A](constructor: SeqConstructor[C], deconstructor: SeqDeconstructor[C])
      extends Binding[BindingType.Seq[C], A]
  object Seq {
    def apply[C[_], A](implicit s: Seq[C, A]): Seq[C, A] = s

    def set[A]: Seq[Set, A] = Seq(SeqConstructor.setConstructor, SeqDeconstructor.setDeconstructor)

    def list[A]: Seq[List, A] = Seq(SeqConstructor.listConstructor, SeqDeconstructor.listDeconstructor)

    def vector[A]: Seq[Vector, A] = Seq(SeqConstructor.vectorConstructor, SeqDeconstructor.vectorDeconstructor)

    def array[A]: Seq[Array, A] = Seq(SeqConstructor.arrayConstructor, SeqDeconstructor.arrayDeconstructor)
  }

  final case class Map[M[_, _], A](constructor: MapConstructor[M], deconstructor: MapDeconstructor[M])
      extends Binding[BindingType.Map[M], A]
  object Map {
    def map[A]: Map[Predef.Map, A] = Map(MapConstructor.map, MapDeconstructor.map)
  }

  implicit val bindingHasConstructor: HasConstructor[Binding] =
    new HasConstructor[Binding] {
      def constructor[A](fa: Binding[BindingType.Record, A]): Constructor[A] = fa match {
        case Binding.Record(constructor, _) => constructor

        case _ => ???
      }
    }

  implicit val bindingHasDeconstructor: HasDeconstructor[Binding] =
    new HasDeconstructor[Binding] {
      def deconstructor[A](fa: Binding[BindingType.Record, A]): Deconstructor[A] = fa match {
        case Binding.Record(_, deconstructor) => deconstructor

        case _ => ???
      }
    }

  implicit val bindingHasMatchers: HasMatchers[Binding] =
    new HasMatchers[Binding] {
      def matchers[A](fa: Binding[BindingType.Variant, A]): Matchers[A] = fa match {
        case Binding.Variant(_, matchers) => matchers
        case _                            => ???
      }
    }

  implicit val bindingHasSeqConstructor: HasSeqConstructor[Binding] =
    new HasSeqConstructor[Binding] {
      def constructor[C[_], A](fa: Binding[BindingType.Seq[C], A]): SeqConstructor[C] = fa match {
        case Binding.Seq(constructor, _) => constructor
        case _                           => ???
      }
    }

  implicit val bindingHasSeqDeconstructor: HasSeqDeconstructor[Binding] =
    new HasSeqDeconstructor[Binding] {
      def deconstructor[C[_], A](fa: Binding[BindingType.Seq[C], A]): SeqDeconstructor[C] = fa match {
        case Binding.Seq(_, deconstructor) => deconstructor
        case _                             => ???
      }
    }

  implicit val bindingHasMapConstructor: HasMapConstructor[Binding] =
    new HasMapConstructor[Binding] {
      def constructor[M[_, _], A](fa: Binding[BindingType.Map[M], A]): MapConstructor[M] = fa match {
        case Binding.Map(constructor, _) => constructor

        case _ => ???
      }
    }

  implicit val bindingHasMapDeconstructor: HasMapDeconstructor[Binding] =
    new HasMapDeconstructor[Binding] {
      def deconstructor[M[_, _], A](fa: Binding[BindingType.Map[M], A]): MapDeconstructor[M] = fa match {
        case Binding.Map(_, deconstructor) => deconstructor

        case _ => ???
      }
    }

  implicit val bindingIsBinding: IsBinding[Binding] = 
    new IsBinding[Binding] {
      def apply[T, A](fa: Binding[T, A]): Binding[T, A] = fa

      def unapply[T, A](fa: Binding[T, A]): Binding[T, A] = fa
    }
}
