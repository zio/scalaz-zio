package zio.blocks.schema

import zio.blocks.schema.binding._

import RegisterOffset.RegisterOffset

object Main {
  final case class Person(name: String, age: Int, address: String, childrenAges: List[Int])

  object Person {
    // Proposed macros:
    //
    // implicit val personSchema: Schema[Person] = deriveSchema[Person]
    //
    // val name          = field[Person](_.name)
    // val age           = field[Person](_.age)
    // val address       = field[Person](_.address)
    // val childrenAges  = field[Person](_.childrenAges)
    // val childrenAges2 = field[Person](_.childrenAges).list // Traversal
    // val left = caseOf[Either[String, Int], Left[String, Int]]

    val constructor: Constructor[Person] =
      new Constructor[Person] {
        def size: RegisterOffset = RegisterOffset(ints = 1, objects = 3)

        def construct(in: Registers, baseOffset: RegisterOffset): Person = {
          val name    = in.getObject(baseOffset, 0).asInstanceOf[String]
          val age     = in.getInt(baseOffset, 0)
          val address = in.getObject(baseOffset, 1).asInstanceOf[String]
          val ages    = in.getObject(baseOffset, 2).asInstanceOf[List[Int]]

          Person(name, age, address, ages)
        }
      }

    val deconstructor: Deconstructor[Person] =
      new Deconstructor[Person] {
        def size: RegisterOffset = RegisterOffset(ints = 1, objects = 3)

        def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Person): Unit = {
          out.setObject(baseOffset, 0, in.name)
          out.setInt(baseOffset, 0, in.age)
          out.setObject(baseOffset, 1, in.address)
          out.setObject(baseOffset, 2, in.childrenAges)
        }
      }

    val personRecord =
      Reflect.Record(
        List[Term[Binding, Person, ?]](
          Term("name", Reflect.string[Binding], Doc.Empty, List.empty),
          Term("age", Reflect.int[Binding], Doc.Empty, List.empty),
          Term("address", Reflect.string[Binding], Doc.Empty, List.empty),
          Term("childrenAges", Reflect.list[Binding, Int](Reflect.int[Binding]), Doc.Empty, List.empty)
        ),
        TypeName(Namespace(List("example"), List.empty), "Person"),
        Binding.Record(constructor, deconstructor),
        Doc.Empty,
        List.empty
      )

    val name: Lens.Bound[Person, String] =
      Lens.Root(personRecord, personRecord.fields(0).asInstanceOf[Term.Bound[Person, String]])
    val age: Lens.Bound[Person, Int] =
      Lens.Root(personRecord, personRecord.fields(1).asInstanceOf[Term.Bound[Person, Int]])
    val address: Lens.Bound[Person, String] =
      Lens.Root(personRecord, personRecord.fields(2).asInstanceOf[Term.Bound[Person, String]])
    val childrenAges: Traversal.Bound[Person, Int] =
      (Lens.Root(personRecord, personRecord.fields(3).asInstanceOf[Term.Bound[Person, List[Int]]])).list
  }

  import Person._

  def main(args: Array[String]): Unit = {
    personRecord.registers.foreach(println)
    personRecord.fields.foreach(println)

    val person = Person("John", 30, "123 Main St", List(5, 7, 9))

    println("name:    " + Person.name.get(person))
    println("age:     " + Person.age.get(person))
    println("address: " + Person.address.get(person))

    // (a)(b)(c)
    // a > b > c

    val newPerson = Person.name.set(person, "Jane")

    println("newPerson: " + newPerson)

    val newPerson2 = Person.childrenAges.modify(person, _ + 1)

    println("newPerson2: " + newPerson2)
  }
}
