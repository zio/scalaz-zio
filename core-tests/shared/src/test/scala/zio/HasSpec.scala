package zio

import zio.test.Assertion._
import zio.test._

object HasSpec extends ZIOBaseSpec {
  trait Animal
  trait Dog   extends Animal
  trait Cat   extends Animal
  trait Bunny extends Animal

  val dog1: Dog     = new Dog { override val toString = "dog1" }
  val dog2: Dog     = new Dog { override val toString = "dog2" }
  val cat1: Cat     = new Cat { override val toString = "cat1" }
  val cat2: Cat     = new Cat { override val toString = "cat2" }
  val bunny1: Bunny = new Bunny { override val toString = "bunny1" }

  val any: Any = ()

  val hasDog1: Has[Dog] = Has(dog1)
  val hasCat1: Has[Cat] = Has(cat1)

  trait IList[+A]

  val dogs1: IList[Dog]      = new IList[Dog] { override val toString = "dogs1" }
  val dogs2: IList[Dog]      = new IList[Dog] { override val toString = "dogs2" }
  val cats1: IList[Cat]      = new IList[Cat] { override val toString = "cats1" }
  val cats2: IList[Cat]      = new IList[Cat] { override val toString = "cats2" }
  val bunnies1: IList[Bunny] = new IList[Bunny] { override val toString = "animals1" }

  trait PetHotel[-A]

  val animalHotel1: PetHotel[Animal] = new PetHotel[Animal] { override val toString = "animalHotel1" }
  val dogHotel1: PetHotel[Dog]       = new PetHotel[Dog] { override val toString = "dogHotel1" }
  val dogHotel2: PetHotel[Dog]       = new PetHotel[Dog] { override val toString = "dogHotel2" }
  val catHotel1: PetHotel[Cat]       = new PetHotel[Cat] { override val toString = "catHotel1" }
  val catHotel2: PetHotel[Cat]       = new PetHotel[Cat] { override val toString = "catHotel2" }
  val bunnyHotel1: PetHotel[Bunny]   = new PetHotel[Bunny] { override val toString = "bunnyHotel1" }

  def spec: ZSpec[Environment, Failure] = suite("HasSpec")(
    suite("Has.combine compilation")(
      test("any/any") {
        final case class Capture[A](a: A)

        def isAny[A](a: A)(implicit ev: A =:= Capture[Any]) =
          assertTrue(a != null)

        val combined = Capture(Has.combine(any, any))

        isAny(combined)
      },
      test("has/Any") {
        val combined: Has[Cat] = Has.combine(hasCat1, any)

        assertTrue(combined != null)
      },
      test("Any/Has") {
        val combined: Has[Cat] = Has.combine(any, hasCat1)

        assertTrue(combined != null)
      },
      test("has/Int") {
        val combined: Has[Cat] with Has[Int] = Has.combine(hasCat1, 42)

        assertTrue(combined != null)
      },
      test("Int/has") {
        val combined: Has[Cat] with Has[Int] = Has.combine(42, hasCat1)

        assertTrue(combined != null)
      },
      test("has/has") {
        val combined: Has[Cat] with Has[Dog] = Has.combine(hasCat1, hasDog1)

        assertTrue(combined != null)
      },
      test("int/String") {
        val combined: Has[Int] with Has[String] = Has.combine(42, "foo")

        assertTrue(combined != null)
      }
    ),
    suite("monomorphic types")(
      test("Modules sharing common parent are independent") {
        val hasBoth = Has(dog1).add[Cat](cat1)

        val dog = hasBoth.get[Dog]
        val cat = hasBoth.get[Cat]

        assert(dog)(anything) && assert(cat)(anything)
      },
      test("Siblings can be updated independently") {
        val whole: Has[Dog] with Has[Cat] = Has(dog1).add(cat1)

        val updated: Has[Dog] with Has[Cat] = whole.update[Dog](_ => dog2).update[Cat](_ => cat2)

        assert(updated.size)(equalTo(2)) &&
        assert(updated.get[Dog])(equalTo(dog2)) &&
        assert(updated.get[Cat])(equalTo(cat2))
      },
      test("Prune will delete what is not known about") {
        val whole: Has[Dog] with Has[Cat] = Has(dog1).add(cat1)

        assert(whole.size)(equalTo(2)) &&
        assert((whole: Has[Dog]).prune.size)(equalTo(1)) &&
        assert((whole: Has[Cat]).prune.size)(equalTo(1))
      },
      test("Union will prune what is not known about on RHS") {
        val unioned = Has(dog1) union ((Has(dog2).add(bunny1)): Has[Bunny])

        assert(unioned.get[Dog])(equalTo(dog1)) &&
        assert(unioned.size)(equalTo(2))
      }
    ),
    suite("covariant types")(
      test("Modules sharing common parent are independent") {
        val hasBoth = Has(dogs1).add[IList[Cat]](cats1)

        val dogs = hasBoth.get[IList[Dog]]
        val cats = hasBoth.get[IList[Cat]]

        assert(dogs)(anything) && assert(cats)(anything)
      },
      test("Siblings can be updated independently") {
        val whole: Has[IList[Dog]] with Has[IList[Cat]] = Has(dogs1).add(cats1)

        val updated: Has[IList[Dog]] with Has[IList[Cat]] =
          whole.update[IList[Dog]](_ => dogs2).update[IList[Cat]](_ => cats2)

        assert(updated.size)(equalTo(2)) &&
        assert(updated.get[IList[Dog]])(equalTo(dogs2)) &&
        assert(updated.get[IList[Cat]])(equalTo(cats2))
      },
      test("Prune will delete what is not known about") {
        val whole: Has[IList[Dog]] with Has[IList[Cat]] = Has(dogs1).add(cats1)

        assert(whole.size)(equalTo(2)) &&
        assert((whole: Has[IList[Dog]]).prune.size)(equalTo(1)) &&
        assert((whole: Has[IList[Cat]]).prune.size)(equalTo(1))
      },
      test("Union will prune what is not known about on RHS") {
        val unioned = Has(dogs1) union ((Has(dogs2).add(bunnies1)): Has[IList[Bunny]])

        assert(unioned.get[IList[Dog]])(equalTo(dogs1)) &&
        assert(unioned.size)(equalTo(2))
      }
    ),
    suite("contravariant types")(
      test("Modules sharing common parent are independent") {
        val hasBoth = Has(dogHotel1).add[PetHotel[Cat]](catHotel1)

        val dogHotel = hasBoth.get[PetHotel[Dog]]
        val catHotel = hasBoth.get[PetHotel[Cat]]

        assert(dogHotel)(anything) && assert(catHotel)(anything)
      },
      test("Siblings can be updated independently") {
        val whole: Has[PetHotel[Dog]] with Has[PetHotel[Cat]] = Has(dogHotel1).add(catHotel1)

        val updated: Has[PetHotel[Dog]] with Has[PetHotel[Cat]] =
          whole.update[PetHotel[Dog]](_ => dogHotel2).update[PetHotel[Cat]](_ => catHotel2)

        assert(updated.size)(equalTo(2)) &&
        assert(updated.get[PetHotel[Dog]])(equalTo(dogHotel2)) &&
        assert(updated.get[PetHotel[Cat]])(equalTo(catHotel2))
      },
      test("Prune will delete what is not known about") {
        val whole: Has[PetHotel[Dog]] with Has[PetHotel[Cat]] = Has(dogHotel1).add(catHotel1)

        assert(whole.size)(equalTo(2)) &&
        assert((whole: Has[PetHotel[Dog]]).prune.size)(equalTo(1)) &&
        assert((whole: Has[PetHotel[Cat]]).prune.size)(equalTo(1))
      },
      test("Union will prune what is not known about on RHS") {
        val unioned = Has(dogHotel1) union ((Has(dogHotel2).add(bunnyHotel1)): Has[PetHotel[Bunny]])

        assert(unioned.get[PetHotel[Dog]])(equalTo(dogHotel1)) &&
        assert(unioned.size)(equalTo(2))
      }
    )
  )
}
