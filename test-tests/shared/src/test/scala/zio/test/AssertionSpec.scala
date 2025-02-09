package zio.test

import zio.test.Assertion._
import zio.{Chunk, Exit, ZIO}

import scala.collection.immutable.SortedSet
import scala.util.{Failure, Success}

object AssertionSpec extends ZIOBaseSpec {

  val failing = TestAspect.failing

  def spec: Spec[Any, TestFailure[Any]] =
    suite("AssertionSpec")(
      test("and must succeed when both assertions are satisfied") {
        assert(sampleUser)(nameStartsWithU && ageGreaterThan20)
      },
      test("and must fail when one of assertions is not satisfied") {
        assert(sampleUser)(nameStartsWithA && ageGreaterThan20)
      } @@ failing,
      test("anything must always succeeds") {
        assert(42)(anything)
      },
      test("approximatelyEquals must succeed when number is within range") {
        assert(5.5)(approximatelyEquals(5.0, 3.0))
      },
      test("approximatelyEquals must fail when number is not within range") {
        assert(50.0)(approximatelyEquals(5.0, 3.0))
      } @@ failing,
      test("contains must succeed when iterable contains specified element") {
        assert(Seq("zio", "scala"))(contains("zio"))
      },
      test("contains must fail when iterable does not contain specified element") {
        assert(Seq("zio", "scala"))(contains("java"))
      } @@ failing,
      test("containsString must succeed when string is found") {
        assert("this is a value")(containsString("is a"))
      },
      test("containsString must return false when the string is not contained") {
        assert("this is a value")(containsString("_NOTHING_"))
      } @@ failing,
      test("dies must succeed when exception satisfy specified assertion") {
        assert(Exit.die(someException))(dies(equalTo(someException)))
      },
      test("dies must fail when exception does not satisfy specified assertion") {
        assert(Exit.die(new RuntimeException("Bam!")))(dies(equalTo(someException)))
      } @@ failing,
      test("diesWithA must succeed when given type assertion is correct") {
        assert(Exit.die(customException))(diesWithA[CustomException])
      },
      test("diesWithA must be agnostic to failure type") {
        assertZIO(
          ZIO
            .die(customException)
            .flatMap(_ => ZIO.fromEither(Right[String, List[Int]](List(1, 2, 3))))
            .exit
        )(diesWithA[CustomException])
      },
      test("endWith must succeed when the supplied value ends with the specified sequence") {
        assert(List(1, 2, 3, 4, 5))(endsWith(List(3, 4, 5)))
      },
      test("startsWith must fail when the supplied value does not end with the specified sequence") {
        assert(List(1, 2, 3, 4, 5))(endsWith(List(1, 2, 3)))
      } @@ failing,
      test("endsWithString must succeed when the supplied value ends with the specified string") {
        assert("zio")(endsWithString("o"))
      },
      test("endsWithString must fail when the supplied value does not end with the specified string") {
        assert("zio")(endsWithString("z"))
      } @@ failing,
      test("equalsIgnoreCase must succeed when the supplied value matches") {
        assert("Some String")(equalsIgnoreCase("some string"))
      },
      test("equalsIgnoreCase must fail when the supplied value does not match") {
        assert("Some Other String")(equalsIgnoreCase("some string"))
      } @@ failing,
      test("equalTo must succeed when value equals specified value") {
        assert(42)(equalTo(42))
      },
      test("equalTo must fail when value does not equal specified value") {
        assert(0)(equalTo(42))
      } @@ failing,
      test("equalTo must succeed when array equals specified array") {
        assert(Array(1, 2, 3))(equalTo(Array(1, 2, 3)))
      },
      test("equalTo must fail when array does not equal specified array") {
        assert(Array(1, 2, 3))(equalTo(Array(1, 2, 4)))
      } @@ failing,
      test("equalTo must not have type inference issues") {
        assert(List(1, 2, 3).filter(_ => false))(equalTo(List.empty))
      },
      test("equalTo must not compile when comparing two unrelated types") {
        val result = typeCheck("assert(1)(equalTo(\"abc\"))")
        assertZIO(result)(
          isLeft(
            (containsString("found   : zio.test.Assertion[String]") &&
              containsString("required: zio.test.Assertion[Int]")) ||
              containsString(
                "String and Int are unrelated types"
              )
          )
        )
      } @@ TestAspect.scala2Only,
      test("equalTo must print out shortened value when using simple class") {
        val probe: SampleUser    = SampleUser("User", 42)
        val expected: SampleUser = SampleUser("User", 50)
        assert(probe)(equalTo(expected))
      } @@ TestAspect.failing[String] {
        case TestFailure.Assertion(result, _) => {
          val failures            = FailureCase.fromTrace(result.failures.get, Chunk.empty)
          val errorString: String = failures(0).errorMessage.lines(0).fragments(0).text
          errorString == "SampleUser.age : expected '50' got '42'\n"
        }
        case _ => false
      },
      test("equalTo must print out shortened error value when using nested class") {
        val probe: Person    = Person("John", 30, Address("New York", "123 Main St"))
        val expected: Person = Person("John", 30, Address("San Francisco", "456 Oak St"))
        assert(probe)(equalTo(expected))
      } @@ TestAspect.failing[String] {
        case TestFailure.Assertion(result, _) => {
          val failures            = FailureCase.fromTrace(result.failures.get, Chunk.empty)
          val errorString: String = failures(0).errorMessage.lines(0).fragments(0).text
          errorString == "Person.address.city : expected 'San Francisco' got 'New York'\nPerson.address.street : expected '456 Oak St' got '123 Main St'\n"
        }
        case _ => false
      },
      test("equalTo must print out shortened value when using list inside class") {
        val probe: Gambler    = Gambler("User", List(100, 200))
        val expected: Gambler = Gambler("User", List(100, 300, 500))
        assert(probe)(equalTo(expected))
      } @@ TestAspect.failing[String] {
        case TestFailure.Assertion(result, _) => {
          val failures            = FailureCase.fromTrace(result.failures.get, Chunk.empty)
          val errorString: String = failures(0).errorMessage.lines(0).fragments(0).text
          errorString == "Gambler.bets[1] : expected '300' got '200'\nGambler.bets[2] : expected '500' got 'null'\n"
        }
        case _ => false
      },
      test("equalTo should succeed for same CharSequence value") {
        val probe: CharSequence = "test"
        assert(probe)(equalTo(probe))
      },
      test("equalTo should succeed for CharSequence value compared with String value") {
        val probe: CharSequence = "test"
        assert(probe)(equalTo("test"))
      },
      test("equalTo should fail for difference CharSequence values") {
        val probe: CharSequence    = "test"
        val expected: CharSequence = "test123"
        assert(probe)(equalTo(expected))
      } @@ failing,
      test("exists must succeed when at least one element of iterable satisfy specified assertion") {
        assert(Seq(1, 42, 5))(exists(equalTo(42)))
      },
      test("exists must fail when all elements of iterable do not satisfy specified assertion") {
        assert(Seq(1, 42, 5))(exists(equalTo(0)))
      } @@ failing,
      test("exists must fail when iterable is empty") {
        assert(Seq[String]())(exists(hasField("length", _.length, isWithin(0, 3))))
      } @@ failing,
      test("exists must fail when inner assertion throws an exception") {
        assert(Seq(1, 2, 3))(exists(assertion("bad_assertion")(i => if (i == 1) ??? else i == 2)))
      } @@ failing,
      test("exists must not fail when inner assertion throws an exception after predicate is satisfied") {
        assert(Seq(1, 2, 3))(exists(assertion("bad_assertion")(i => if (i == 3) ??? else i == 2)))
      },
      test("fails must succeed when error value satisfy specified assertion") {
        assert(Exit.fail("Some Error"))(fails(equalTo("Some Error")))
      },
      test("fails must fail when error value does not satisfy specified assertion") {
        assert(Exit.fail("Other Error"))(fails(equalTo("Some Error")))
      } @@ failing,
      test("failsWithA must succeed when given type assertion is correct") {
        assert(Exit.fail(customException))(failsWithA[CustomException])
      },
      test("failsWithA must fail when given type assertion is incorrect") {
        assert(Exit.fail(someException))(failsWithA[CustomException])
      } @@ failing,
      test("forall must succeed when all elements of iterable satisfy specified assertion") {
        assert(Seq("a", "bb", "ccc"))(forall(hasField("length", _.length, isWithin(0, 3))))
      },
      test("forall must fail when one element of iterable do not satisfy specified assertion") {
        assert(Seq("a", "bb", "dddd"))(forall(hasField("length", _.length, isWithin(0, 3))))
      } @@ failing,
      test("forall must succeed when an iterable is empty") {
        assert(Seq[String]())(forall(hasField("length", _.length, isWithin(0, 3))))
      },
      test("forall must work with iterables that are not lists") {
        assert(SortedSet(1, 2, 3))(forall(isGreaterThan(0)))
      },
      test("hasSameElementsDistinct must succeed when iterable contains the specified elements") {
        assert(Seq(1, 2, 3))(hasSameElementsDistinct(Set(1, 2, 3)))
      },
      test(
        "hasSameElementsDistinct must succeed when iterable contains duplicates of the specified elements"
      ) {
        assert(Seq("a", "a", "b", "b", "b", "c", "c", "c", "c", "c"))(
          hasSameElementsDistinct(Set("a", "b", "c"))
        )
      },
      test("hasSameElementsDistinct must succeed when specified elements contain duplicates") {
        assert(Seq(1, 2, 3))(hasSameElementsDistinct(Seq(1, 2, 3, 3)))
      },
      test("hasSameElementsDistinct must fail when iterable does not have all specified elements") {
        assert(Seq(1, 2, 3, 4))(hasSameElementsDistinct(Set(1, 2, 3, 4, 5)))
      } @@ failing,
      test("hasSameElementsDistinct must fail when iterable contains unspecified elements") {
        assert(Seq(1, 2, 3, 4))(hasSameElementsDistinct(Set(1, 2, 3)))
      } @@ failing,
      test("hasAt must fail when an index is outside of a sequence range") {
        assert(Seq(1, 2, 3))(hasAt(-1)(anything))
      } @@ failing,
      test("hasAt must fail when an index is outside of a sequence range 2") {
        assert(Seq(1, 2, 3))(hasAt(3)(anything))
      } @@ failing,
      test("hasAt must fail when a value is not equal to a specific assertion") {
        assert(Seq(1, 2, 3))(hasAt(1)(equalTo(1)))
      } @@ failing,
      test("hasAt must succeed when a value is equal to a specific assertion") {
        assert(Seq(1, 2, 3))(hasAt(1)(equalTo(2)))
      },
      test("hasAtLeastOneOf must succeed when iterable contains one of the specified elements") {
        assert(Seq("zio", "scala"))(hasAtLeastOneOf(Set("zio", "test", "java")))
      },
      test("hasAtLeastOneOf must succeed when iterable contains more than one of the specified elements") {
        assert(Seq("zio", "scala"))(hasAtLeastOneOf(Set("zio", "test", "scala")))
      },
      test("hasAtLeastOneOf must fail when iterable does not contain a specified element") {
        assert(Seq("zio", "scala"))(hasAtLeastOneOf(Set("python", "rust")))
      } @@ failing,
      test("hasAtMostOneOf must succeed when iterable contains one of the specified elements") {
        assert(Seq("zio", "scala"))(hasAtMostOneOf(Set("zio", "test", "java")))
      },
      test("hasAtMostOneOf must succeed when iterable does not contain a specified element") {
        assert(Seq("zio", "scala"))(hasAtMostOneOf(Set("python", "rust")))
      },
      test("hasAtMostOneOf must fail when iterable contains more than one of the specified elements") {
        assert(Seq("zio", "scala"))(hasAtMostOneOf(Set("zio", "test", "scala")))
      } @@ failing,
      test("hasField must succeed when field value satisfy specified assertion") {
        assert(SampleUser("User", 23))(hasField[SampleUser, Int]("age", _.age, isWithin(0, 99)))
      },
      test("hasFirst must fail when an iterable is empty") {
        assert(Seq())(hasFirst(anything))
      } @@ failing,
      test("hasFirst must succeed when a head is equal to a specific assertion") {
        assert(Seq(1, 2, 3))(hasFirst(equalTo(1)))
      },
      test("hasFirst must fail when a head is not equal to a specific assertion") {
        assert(Seq(1, 2, 3))(hasFirst(equalTo(100)))
      } @@ failing,
      test("hasIntersection must succeed when intersection satisfies specified assertion") {
        assert(Seq(1, 2, 3))(hasIntersection(Seq(3, 4, 5))(hasSize(equalTo(1))))
      },
      test("hasIntersection must succeed when empty intersection satisfies specified assertion") {
        assert(Seq(1, 2, 3))(hasIntersection(Seq(4, 5, 6))(isEmpty))
      },
      test("hasIntersection must fail when intersection does not satisfy specified assertion") {
        assert(Seq(1, 2, 3))(hasIntersection(Seq(3, 4, 5))(isEmpty))
      } @@ failing,
      test("hasKey must succeed when map has key with value satisfying specified assertion") {
        assert(Map("scala" -> 1))(hasKey("scala", equalTo(1)))
      },
      test("hasKey must fail when map does not have the specified key") {
        assert(Map("scala" -> 1))(hasKey("java", equalTo(1)))
      } @@ failing,
      test("hasKey must fail when map has key with value not satisfying specified assertion") {
        assert(Map("scala" -> 1))(hasKey("scala", equalTo(-10)))
      } @@ failing,
      test("hasKey must succeed when map has the specified key") {
        assert(Map("scala" -> 1))(hasKey("scala"))
      },
      test("hasKeys must succeed when map has keys satisfying the specified assertion") {
        assert(Map("scala" -> 1, "java" -> 1))(hasKeys(hasAtLeastOneOf(Set("scala", "java"))))
      },
      test("hasKeys must fail when map has keys not satisfying the specified assertion") {
        assert(Map("scala" -> 1, "java" -> 1))(hasKeys(contains("bash")))
      } @@ failing,
      test("hasLast must fail when an iterable is empty") {
        assert(Seq())(hasLast(anything))
      } @@ failing,
      test("hasLast must succeed when a last is equal to a specific assertion") {
        assert(Seq(1, 2, 3))(hasLast(equalTo(3)))
      },
      test("hasLast must fail when a last is not equal to specific assertion") {
        assert(Seq(1, 2, 3))(hasLast(equalTo(100)))
      } @@ failing,
      test("hasNoneOf must succeed when iterable does not contain one of the specified elements") {
        assert(Seq("zio", "scala"))(hasNoneOf(Set("python", "rust")))
      },
      test("hasNoneOf must succeed when iterable is empty") {
        assert(Seq.empty[String])(hasNoneOf(Set("zio", "test", "java")))
      },
      test("hasNoneOf must fail when iterable contains a specified element") {
        assert(Seq("zio", "scala"))(hasNoneOf(Set("zio", "test", "scala")))
      } @@ failing,
      test("hasOneOf must succeed when iterable contains exactly one of the specified elements") {
        assert(Seq("zio", "scala"))(hasOneOf(Set("zio", "test", "java")))
      },
      test("hasOneOf must fail when iterable contains more than one of the specified elements") {
        assert(Seq("zio", "scala"))(hasOneOf(Set("zio", "test", "scala")))
      } @@ failing,
      test("hasOneOf must fail when iterable does not contain at least one of the specified elements") {
        assert(Seq("zio", "scala"))(hasOneOf(Set("python", "rust")))
      } @@ failing,
      test("hasSameElements must succeed when both iterables contain the same elements") {
        assert(Seq(1, 2, 3))(hasSameElements(Seq(1, 2, 3)))
      },
      test("hasSameElements must fail when the iterables do not contain the same elements") {
        assert(Seq(1, 2, 3, 4))(hasSameElements(Seq(1, 2, 3)) && hasSameElements(Seq(1, 2, 3, 4, 5)))
      } @@ failing,
      test("hasSameElements must succeed when both iterables contain the same elements in different order") {
        assert(Seq(4, 3, 1, 2))(hasSameElements(Seq(1, 2, 3, 4)))
      },
      test(
        "hasSameElements must fail when both iterables have the same size, have the same values but they appear a different number of times."
      ) {
        assert(Seq("a", "a", "b", "b", "b", "c", "c", "c", "c", "c"))(
          hasSameElements(Seq("a", "a", "a", "a", "a", "b", "b", "c", "c", "c"))
        )
      } @@ failing,
      test("hasSize must succeed when iterable size is equal to specified assertion") {
        assert(Seq(1, 2, 3))(hasSize(equalTo(3)))
      },
      test("hasSize must fail when iterable size is not equal to specified assertion") {
        assert(Seq(1, 2, 3))(hasSize(equalTo(1)))
      } @@ failing,
      test("hasSize must succeed when chunk size is equal to specified assertion") {
        assert(Chunk(1, 2, 3))(hasSize(equalTo(3)))
      },
      test("hasSize must fail when chunk size is not equal to specified assertion") {
        assert(Chunk(1, 2, 3))(hasSize(equalTo(1)))
      } @@ failing,
      test("hasSizeString must succeed when string size is equal to specified assertion") {
        assert("aaa")(hasSizeString(equalTo(3)))
      },
      test("hasSizeString must fail when string size is not equal to specified assertion") {
        assert("aaa")(hasSizeString(equalTo(2)))
      } @@ failing,
      test("hasSubset must succeed when both iterables contain the same elements") {
        assert(Seq(1, 2, 3))(hasSubset(Set(1, 2, 3)))
      },
      test("hasSubset must succeed when the other iterable is a subset of the iterable") {
        assert(Seq(1, 2, 3, 4))(hasSubset(Set(1, 2, 3)))
      },
      test("hasSubset must succeed when the other iterable is empty") {
        assert(Seq(4, 3, 1, 2))(hasSubset(Set.empty[Int]))
      },
      test("hasSubset must fail when iterable does not contain elements specified in set") {
        assert(Seq(4, 3, 1, 2))(hasSubset(Set(1, 2, 10)))
      } @@ failing,
      test("hasValues must succeed when map has values satisfying the specified assertion") {
        assert(Map("scala" -> 10, "java" -> 20))(hasValues(hasAtLeastOneOf(Set(0, 10))))
      },
      test("hasValues must fail when map has values not satisfying the specified assertion") {
        assert(Map("scala" -> 10, "java" -> 20))(hasValues(contains(0)))
      } @@ failing,
      test("isCase must fail when unapply fails (returns None)") {
        assert(42)(isCase[Int, String](termName = "term", _ => None, equalTo("number: 42")))
      } @@ failing,
      test("isCase must succeed when unapplied Proj satisfy specified assertion")(
        assert(sampleUser)(
          isCase[SampleUser, (String, Int)](
            termName = "SampleUser",
            { case SampleUser(name, age) => Some((name, age)) },
            equalTo((sampleUser.name, sampleUser.age))
          )
        )
      ),
      test("isDistinct must succeed when iterable is distinct") {
        assert(Seq(1, 2, 3, 4, 0))(isDistinct)
      },
      test("isDistinct must succeed for empty iterable") {
        assert(Seq.empty[Int])(isDistinct)
      },
      test("isDistinct must succeed for singleton iterable") {
        assert(Seq(1))(isDistinct)
      },
      test("isDistinct must fail when iterable is not distinct") {
        assert(Seq(1, 2, 3, 3, 4))(isDistinct)
      } @@ failing,
      test("isEmpty must succeed when the traversable is empty") {
        assert(Seq())(isEmpty)
      },
      test("isEmpty must fail when the traversable is not empty") {
        assert(Seq(1, 2, 3))(isEmpty)
      } @@ failing,
      test("isEmptyString must succeed when the string is empty") {
        assert("")(isEmptyString)
      },
      test("isEmptyString must fail when the string is not empty") {
        assert("some string")(isEmptyString)
      } @@ failing,
      test("isFalse must succeed when supplied value is false") {
        assert(false)(isFalse)
      },
      test("isFailure must succeed when Failure value satisfies the specified assertion") {
        assert(Failure(new Exception("oh no!")))(isFailure(hasMessage(equalTo("oh no!"))))
      },
      test("isFailure must succeed when Try value is Failure") {
        assert(Failure(new Exception("oh no!")))(isFailure)
      },
      test("isGreaterThan must succeed when specified value is greater than supplied value") {
        assert(42)(isGreaterThan(0))
      },
      test("isGreaterThan must fail when specified value is less than or equal supplied value") {
        assert(42)(isGreaterThan(42))
      } @@ failing,
      test("isGreaterThanEqualTo must succeed when specified value is greater than or equal supplied value") {
        assert(42)(isGreaterThanEqualTo(42))
      },
      test("isLeft must succeed when supplied value is Left and satisfy specified assertion") {
        assert(Left(42))(isLeft(equalTo(42)))
      },
      test("isLeft must succeed when supplied value is Left") {
        assert(Left(42))(isLeft)
      },
      test("isLeft must fail when supplied value is Right") {
        assert(Right(-42))(isLeft)
      } @@ failing,
      test("isLessThan must succeed when specified value is less than supplied value") {
        assert(0)(isLessThan(42))
      },
      test("isLessThan must fail when specified value is greater than or equal supplied value") {
        assert(42)(isLessThan(42))
      } @@ failing,
      test("isLessThanEqualTo must succeed when specified value is less than or equal supplied value") {
        assert(42)(isLessThanEqualTo(42))
      },
      test("isNegative must succeed when number is negative") {
        assert(-10)(isNegative)
      },
      test("isNegative must fail when number is zero") {
        assert(0)(isNegative)
      } @@ failing,
      test("isNegative must fail when number is positive") {
        assert(10)(isNegative)
      } @@ failing,
      test("isNone must succeed when specified value is None") {
        assert(None)(isNone)
      },
      test("isNone must fail when specified value is not None") {
        assert(Some(42))(isNone)
      } @@ failing,
      test("isNonEmpty must succeed when the traversable is not empty") {
        assert(Seq(1, 2, 3))(isNonEmpty)
      },
      test("isNonEmpty must fail when the chunk is empty") {
        assert(Chunk.empty)(isNonEmpty)
      } @@ failing,
      test("isNonEmpty must succeed when the chunk is not empty") {
        assert(Chunk(1, 2, 3))(isNonEmpty)
      },
      test("isNonEmpty must fail when the traversable is empty") {
        assert(Seq())(isNonEmpty)
      } @@ failing,
      test("isNonEmptyString must succeed when the string is not empty") {
        assert("some string")(isNonEmptyString)
      },
      test("isNonEmptyString must fail when the string is empty") {
        assert("")(isNonEmptyString)
      } @@ failing,
      test("isNull must succeed when specified value is null") {
        assert(null)(isNull)
      },
      test("isNull must fail when specified value is not null") {
        assert("not null")(isNull)
      } @@ failing,
      test("isOneOf must succeed when value is equal to one of the specified values") {
        assert('a')(isOneOf(Set('a', 'b', 'c')))
      },
      test("isOneOf must fail when value is not equal to one of the specified values") {
        assert('z')(isOneOf(Set('a', 'b', 'c')))
      } @@ failing,
      test("isPositive must succeed when number is positive") {
        assert(10)(isPositive)
      },
      test("isPositive must fail when number is zero") {
        assert(0)(isPositive)
      } @@ failing,
      test("isPositive must fail when number is negative") {
        assert(-10)(isPositive)
      } @@ failing,
      test("isRight must succeed when supplied value is Right and satisfy specified assertion") {
        assert(Right(42))(isRight(equalTo(42)))
      },
      test("isRight must succeed when supplied value is Right") {
        assert(Right(42))(isRight)
      },
      test("isSome must succeed when supplied value is Some and satisfy specified assertion") {
        assert(Some("zio"))(isSome(equalTo("zio")))
      },
      test("isSome with isOneOf must succeed when assertion is satisfied") {
        assert(Some("zio"))(isSome(isOneOf(Set("zio", "test"))))
      },
      test("isSome must fail when supplied value is None") {
        assert(None)(isSome(equalTo("zio")))
      } @@ failing,
      test("isSome must succeed when supplied value is Some") {
        assert(Some("zio"))(isSome)
      },
      test("isSome must fail when supplied value is None") {
        assert(None)(isSome)
      } @@ failing,
      test("isSorted must succeed when iterable is sorted") {
        assert(Seq(1, 2, 2, 3, 4))(isSorted)
      },
      test("isSorted must succeed for empty iterable") {
        assert(Seq.empty[Int])(isSorted)
      },
      test("isSorted must succeed for singleton iterable") {
        assert(Seq(1))(isSorted)
      },
      test("isSorted must fail when iterable is not sorted") {
        assert(Seq(1, 2, 0, 3, 4))(isSorted)
      } @@ failing,
      test("isSortedReverse must succeed when iterable is reverse sorted") {
        assert(Seq(4, 3, 3, 2, 1))(isSortedReverse)
      },
      test("isSortedReverse must fail when iterable is not reverse sorted") {
        assert(Seq(1, 2, 0, 3, 4))(isSortedReverse)
      } @@ failing,
      test("isSubtype must succeed when value is subtype of specified type") {
        assert(dog)(isSubtype[Animal](anything))
      },
      test("isSubtype must fail when value is supertype of specified type") {
        assert(animal)(isSubtype[Cat](anything))
      } @@ failing,
      test("isSubtype must fail when value is neither subtype nor supertype of specified type") {
        assert(cat)(isSubtype[Dog](anything))
      } @@ failing,
      test("isSubtype must handle primitive types") {
        assert(1)(isSubtype[Int](anything))
      },
      test("isSubtype must handle malformed class names") {
        sealed trait Exception
        object Exception {
          case class MyException() extends Exception
        }
        val exception = new Exception.MyException
        assert(exception)(isSubtype[Exception.MyException](anything))
      },
      test("isSuccess must succeed when Success value satisfies the specified assertion") {
        assert(Success(1))(isSuccess(isPositive[Int]))
      },
      test("isSuccess must succeed when Try value is Success") {
        assert(Success(1))(isSuccess)
      },
      test("isTrue must succeed when supplied value is true") {
        assert(true)(isTrue)
      },
      test("isUnit must succeed when supplied value is ()") {
        assert(())(isUnit)
      },
      test("isUnit must not compile when supplied value is not ()") {
        val result = typeCheck("assert(10)(isUnit)")
        assertZIO(result)(isLeft(anything))
      },
      test("isWithin must succeed when supplied value is within range (inclusive)") {
        assert(10)(isWithin(0, 10))
      },
      test("isWithin must fail when supplied value is out of range") {
        assert(42)(isWithin(0, 10))
      } @@ failing,
      test("isZero must succeed when number is zero") {
        assert(0)(isZero)
      },
      test("isZero must fail when number is not zero") {
        assert(10)(isZero)
      } @@ failing,
      test("matches must succeed when the string matches the regex") {
        assert("(123) 456-7890")(matchesRegex("\\([1-9]{3}\\) [0-9]{3}\\-[0-9]{4}$"))
      },
      test("matches must fail when the string does not match the regex") {
        assert("456-7890")(matchesRegex("\\([1-9]{3}\\) [0-9]{3}\\-[0-9]{4}$"))
      } @@ failing,
      test("negate must succeed when negation of assertion is true") {
        assert(sampleUser)(nameStartsWithA.negate)
      },
      test("nonNegative must succeed when number is positive") {
        assert(10)(nonNegative)
      },
      test("nonNegative must succeed when number is zero") {
        assert(0)(nonNegative)
      },
      test("nonNegative must fail when number is negative") {
        assert(-10)(nonNegative)
      } @@ failing,
      test("nonPositive must succeed when number is negative") {
        assert(-10)(nonPositive)
      },
      test("nonPositive must succeed when number is zero") {
        assert(0)(nonPositive)
      },
      test("nonPositive must fail when number is positive") {
        assert(10)(nonPositive)
      } @@ failing,
      test("not must succeed when negation of specified assertion is true") {
        assert(0)(not(equalTo(42)))
      },
      test("nothing must always fail") {
        assert(42)(nothing)
      } @@ failing,
      test("or must succeed when one of assertions is satisfied") {
        assert(sampleUser)((nameStartsWithA || nameStartsWithU) && ageGreaterThan20)
      },
      test("or must fail when both assertions are not satisfied") {
        assert(sampleUser)(nameStartsWithA || ageLessThan20)
      } @@ failing,
      test("startsWith must succeed when the supplied value starts with the specified sequence") {
        assert(List(1, 2, 3, 4, 5))(startsWith(List(1, 2, 3)))
      },
      test("startsWith must fail when the supplied value does not start with the specified sequence") {
        assert(List(1, 2, 3, 4, 5))(startsWith(List(3, 4, 5)))
      } @@ failing,
      test("startsWithString must succeed when the supplied value starts with the specified string") {
        assert("zio")(startsWithString("z"))
      },
      test("startsWithString must fail when the supplied value does not start with the specified string") {
        assert("zio")(startsWithString("o"))
      } @@ failing,
      test("succeeds must succeed when supplied value is Exit.succeed and satisfy specified assertion") {
        assert(Exit.succeed("Some Error"))(succeeds(equalTo("Some Error")))
      },
      test("succeeds must fail when supplied value is Exit.fail") {
        assert(Exit.fail("Some Error"))(succeeds(equalTo("Some Error")))
      } @@ failing,
      test("test must return true when given element satisfy assertion") {
        assert(nameStartsWithU.test(sampleUser))(isTrue)
      },
      test("test must return false when given element does not satisfy assertion") {
        assert(nameStartsWithA.test(sampleUser))(isFalse)
      },
      test("throws must succeed when given assertion is correct") {
        assert(throw sampleException)(throws(equalTo(sampleException)))
      },
      test("throwsA must succeed when given type assertion is correct") {
        assert(throw customException)(throwsA[CustomException])
      },
      test("should implement equals without exception") {
        assert(nameStartsWithU.equals(new Object))(isFalse)
      },
      test("hasThrowableCause must succeed when supplied value has matching cause") {
        val cause = new Exception("cause")
        val t     = new Exception("result", cause)
        assert(t)(hasThrowableCause(hasMessage(equalTo("cause"))))
      },
      test("hasThrowableCause must fail when supplied value has non-matching cause") {
        val cause = new Exception("something different")
        val t     = new Exception("result", cause)
        assert(t)(hasThrowableCause(hasMessage(equalTo("cause"))))
      } @@ failing,
      test("hasThrowableCause must fail when supplied value does not have a cause") {
        val t = new Exception("result")
        assert(t)(hasThrowableCause(hasMessage(equalTo("cause"))))
      } @@ failing
    )

  case class Person(name: String, age: Int, address: Address)
  case class Address(city: String, street: String)
  case class Gambler(name: String, bets: List[Int])

  case class SampleUser(name: String, age: Int)
  val sampleUser: SampleUser = SampleUser("User", 42)
  val sampleException        = new Exception

  val nameStartsWithA: Assertion[SampleUser]  = hasField("name", _.name.startsWith("A"), isTrue)
  val nameStartsWithU: Assertion[SampleUser]  = hasField("name", _.name.startsWith("U"), isTrue)
  val ageLessThan20: Assertion[SampleUser]    = hasField("age", _.age, isLessThan(20))
  val ageGreaterThan20: Assertion[SampleUser] = hasField("age", _.age, isGreaterThan(20))

  val someException = new RuntimeException("Boom!")

  case class CustomException() extends RuntimeException("Custom Boom!")
  val customException: CustomException = CustomException()

  trait Animal
  trait Dog extends Animal
  trait Cat extends Animal

  val animal: Animal = new Animal {}

  val dog: Dog = new Dog {}
  val cat: Cat = new Cat {}
}
