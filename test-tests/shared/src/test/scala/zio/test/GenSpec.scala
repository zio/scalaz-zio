package zio.test

import zio._
import zio.test.Assertion._
import zio.test.AssertionResult.FailureDetailsResult
import zio.test.GenUtils._
import zio.test.TestAspect.{nonFlaky, scala2Only, setSeed}
import zio.test.{check => Check, checkN => CheckN}

import java.time.{Duration => _, _}
import scala.math.Numeric.DoubleIsFractional

object GenSpec extends ZIOBaseSpec {
  implicit val localDateTimeOrdering: Ordering[LocalDateTime] = _ compareTo _

  def spec: ZSpec[Environment, Failure] = suite("GenSpec")(
    suite("integration tests")(
      test("with bogus even property") {
        val gen = Gen.int(0, 100)

        def test(n: Int): TestResult = {
          val p = n % 2 == 0
          if (p) assert(())(Assertion.anything) else assert(n)(Assertion.nothing)
        }

        assertM(CheckN(100)(gen)(test).map { result =>
          result.failures.fold(false) {
            case BoolAlgebra.Value(FailureDetailsResult(failureDetails, _)) =>
              failureDetails.assertion.head.value.toString == "1"
            case _ => false
          }
        })(isTrue)
      },
      test("with bogus reverse property") {
        val gen = for {
          as <- Gen.int(0, 100).flatMap(Gen.listOfN(_)(Gen.anyInt))
          bs <- Gen.int(0, 100).flatMap(Gen.listOfN(_)(Gen.anyInt))
        } yield (as, bs)

        def test(a: (List[Int], List[Int])): TestResult = a match {
          case (as, bs) =>
            val p = (as ++ bs).reverse == (as.reverse ++ bs.reverse)
            if (p) assert(())(Assertion.anything) else assert((as, bs))(Assertion.nothing)
        }
        assertM(CheckN(100)(gen)(test).map { result =>
          result.failures.fold(false) {
            case BoolAlgebra.Value(FailureDetailsResult(failureDetails, _)) =>
              failureDetails.assertion.head.value.toString == "(List(0),List(1))" ||
                failureDetails.assertion.head.value.toString == "(List(1),List(0))" ||
                failureDetails.assertion.head.value.toString == "(List(0),List(-1))" ||
                failureDetails.assertion.head.value.toString == "(List(-1),List(0))"
            case _ => false
          }
        })(isTrue)
      },
      test("with randomly generated functions") {
        val ints                                           = Gen.listOf(Gen.int(-10, 10))
        val intBooleanFn: Gen[Has[Random], Int => Boolean] = Gen.function(Gen.boolean)

        Check(ints, intBooleanFn)((as, f) => assert(as.takeWhile(f).forall(f))(isTrue))
      },
      test("with multiple parameter function generator") {
        val ints                                       = Gen.anyInt
        val genFn: Gen[Has[Random], (Int, Int) => Int] = Gen.function2(Gen.anyInt)

        def swap[A, B, C](f: (A, B) => C): (B, A) => C =
          (b, a) => f(a, b)

        Check(ints, ints, genFn) { (a, b, f) =>
          val g = swap(swap(f))
          assert(f(a, b))(equalTo(g(a, b)))
        }
      },
      test("with shrinking nonempty list") {
        val gen = Gen.int(1, 100).flatMap(Gen.listOfN(_)(Gen.anyInt))

        def test(a: List[Int]): TestResult = assert(a)(Assertion.nothing)

        assertM(CheckN(100)(gen)(test).map { result =>
          result.failures.fold(false) {
            case BoolAlgebra.Value(FailureDetailsResult(failureDetails, _)) =>
              failureDetails.assertion.head.value.toString == "List(0)"
            case _ => false
          }
        })(isTrue)
      }
    ),
    suite("monad laws")(
      test("monad left identity") {
        assertM(equal(smallInt.flatMap(a => Gen.const(a)), smallInt))(isTrue)
      },
      test("monad right identity") {
        val n = 10

        def f(n: Int): Gen[Has[Random], Int] = Gen.int(-n, n)

        assertM(equal(Gen.const(n).flatMap(f), f(n)))(isTrue)
      },
      test("monad associativity") {
        val fa = Gen.int(0, 2)

        def f(p: Int): Gen[Has[Random], (Int, Int)] =
          Gen.const(p) <*> Gen.int(0, 3)

        def g(p: (Int, Int)): Gen[Has[Random], (Int, Int, Int)] =
          Gen.const(p).zipWith(Gen.int(0, 5)) { case ((x, y), z) => (x, y, z) }

        assertM(equal(fa.flatMap(f).flatMap(g), fa.flatMap(a => f(a).flatMap(g))))(isTrue)
      }
    ),
    suite("sample")(
      test("alphaNumericChar generates numbers and letters") {
        checkSample(Gen.alphaNumericChar)(forall(isTrue), _.map(_.isLetterOrDigit))
      },
      test("alphaNumericString generates numbers and letters") {
        checkSample(Gen.alphaNumericString)(forall(isTrue), _.map(_.forall(_.isLetterOrDigit)))
      },
      test("alphaNumericStringBounded generates strings whose size is in bounds") {
        checkSample(Gen.alphaNumericStringBounded(2, 10))(forall(hasSizeString(isWithin(2, 10))))
      },
      test("anyDayOfWeek generates java.time.DayOfWeek values") {
        checkSample(Gen.anyDayOfWeek)(isTrue, ds => ds.forall(DayOfWeek.values().contains))
      },
      test("anyFiniteDuration generates Duration values") {
        checkSample(Gen.anyFiniteDuration)(isNonEmpty)
      },
      test("anyInstant generates Instant values") {
        checkSample(Gen.anyInstant)(isNonEmpty)
      },
      test("anyLocalDateTime generates LocalDateTime values") {
        checkSample(Gen.anyLocalDateTime)(isNonEmpty)
      },
      test("anyLocalDate generates java.time.LocalDate values") {
        checkSample(Gen.anyLocalDate)(isNonEmpty)
      },
      test("anyLocalTime generates java.time.LocalTime values") {
        checkSample(Gen.anyLocalTime)(isNonEmpty)
      },
      test("anyMonth generates java.time.Month values") {
        checkSample(Gen.anyMonth)(isTrue, ms => ms.forall(Month.values().contains))
      },
      test("anyMonthDay generates java.time.MonthDay values") {
        checkSample(Gen.anyMonthDay)(isNonEmpty)
      },
      test("anyOffsetDateTime generates OffsetDateTime values") {
        checkSample(Gen.anyOffsetDateTime)(isNonEmpty)
      },
      test("anyOffsetTime generates java.time.OffsetTime values") {
        checkSample(Gen.anyOffsetTime)(isNonEmpty)
      },
      test("anyPeriod generates java.time.Period values") {
        checkSample(Gen.anyPeriod)(isNonEmpty)
      },
      test("anyYear generates java.time.Year values") {
        checkSample(Gen.anyYear)(isNonEmpty)
      },
      test("anyYearMonth generates java.time.YearMonth values") {
        checkSample(Gen.anyYearMonth)(isNonEmpty)
      },
      test("anyZonedDateTime generates java.time.ZonedDateTime values") {
        checkSample(Gen.anyZonedDateTime)(isNonEmpty)
      },
      test("anyZoneId generates java.time.ZoneId values") {
        checkSample(Gen.anyZoneId)(isNonEmpty)
      },
      test("anyZoneOffset generates java.time.ZoneOffset values") {
        checkSample(Gen.anyZoneOffset)(isNonEmpty)
      },
      test("bigDecimal generates values in range") {
        val min        = BigDecimal("1.414213562373095048801688724209698")
        val max        = BigDecimal("2.0")
        val bigDecimal = Gen.bigDecimal(min, max)
        checkSample(bigDecimal)(forall(isGreaterThanEqualTo(min) && isLessThanEqualTo(max)))
      },
      test("bigInt generates values in range") {
        val min    = BigInt("1")
        val max    = BigInt("265252859812191058636308480000000")
        val bigInt = Gen.bigInt(min, max)
        checkSample(bigInt)(forall(isGreaterThanEqualTo(min) && isLessThanEqualTo(max)))
      },
      test("boolean generates true and false") {
        checkSample(Gen.boolean)(contains(true) && contains(false))
      },
      test("byte generates values in range") {
        checkSample(Gen.byte(38, 38))(forall(equalTo(38.toByte)))
      },
      test("char generates values in range") {
        checkSample(Gen.char(33, 123))(
          forall(isGreaterThanEqualTo(33) && isLessThanEqualTo(123)),
          _.map(_.toInt)
        )
      },
      test("chunkOf generates sizes in range") {
        checkSample(Gen.chunkOf(smallInt))(
          forall(isGreaterThanEqualTo(0) && isLessThanEqualTo(100)),
          _.map(_.length)
        )
      },
      test("chunkOfBounded generates chunks whose size is in bounds") {
        checkSample(Gen.chunkOfBounded(2, 10)(smallInt))(forall(hasSize(isWithin(2, 10))))
      },
      test("chunkOf1 generates nonempty chunks") {
        checkSample(Gen.chunkOf1(smallInt), size = 0)(forall(isNonEmpty), _.map(_.toChunk))
      },
      test("chunkOfN generates chunks of correct size") {
        checkSample(Gen.chunkOfN(10)(smallInt))(forall(equalTo(10)), _.map(_.length))
      },
      test("collect collects values a partial function is defined at") {
        checkSample(smallInt.collect { case n if n % 2 == 0 => n })(forall(equalTo(0)), _.map(_ % 2))
      },
      test("const generates constant value") {
        checkSample(Gen.const("constant"))(forall(equalTo("constant")))
      },
      test("double generates values in range") {
        checkSample(Gen.double(5.0, 9.0))(forall(isGreaterThanEqualTo(5.0) && isLessThan(9.0)))
      },
      test("exponential generates values between 0 and positive infinity") {
        checkSample(Gen.exponential)(forall(isGreaterThanEqualTo(0.0)))
      },
      test("filter filters values according to predicate") {
        checkSample(smallInt.filter(_ % 2 == 0))(forall(equalTo(0)), _.map(_ % 2))
      },
      test("finiteDuration generates values in range") {
        val min = 42.minutes + 23222.nanos
        val max = 3.hours + 30.seconds + 887999.nanos
        checkSample(Gen.finiteDuration(min, max))(forall(isGreaterThanEqualTo(min) && isLessThanEqualTo(max)))
      },
      test("function generates different functions") {
        val gen = for {
          f <- genStringIntFn
          g <- genStringIntFn
          s <- Gen.string(Gen.anyChar)
        } yield f(s) == g(s)
        checkSample(gen)(isTrue, _.exists(!_))
      },
      test("function generates functions that are not constant") {
        val gen = for {
          f  <- genStringIntFn
          s1 <- Gen.string(Gen.anyChar)
          s2 <- Gen.string(Gen.anyChar)
        } yield f(s1) == f(s2)
        checkSample(gen)(isTrue, _.exists(!_))
      },
      test("function generates referentially transparent functions") {
        val gen = for {
          f <- genStringIntFn
          s <- Gen.string(Gen.anyChar)
        } yield f(s) == f(s)
        checkSample(gen)(isTrue, _.forall(identity))
      },
      test("instant generates values in range") {
        val min = Instant.ofEpochSecond(-38457693893669L, 435345)
        val max = Instant.ofEpochSecond(74576982873324L, 345345345)
        checkSample(Gen.instant(min, max))(forall(isGreaterThanEqualTo(min) && isLessThanEqualTo(max)))
      },
      test("int generates values in range") {
        checkSample(smallInt)(forall(isGreaterThanEqualTo(-10) && isLessThanEqualTo(10)))
      },
      test("int is safe for ranges greater than Int.MaxValue") {
        val gen = Gen.int(0, Int.MaxValue)
        checkSample(gen)(forall(isGreaterThanEqualTo(0) && isLessThanEqualTo(Int.MaxValue)))
      },
      test("large generates sizes in range") {
        val gen = Gen.large(Gen.listOfN(_)(Gen.int(-10, 10)))
        checkSample(gen)(forall(isLessThanEqualTo(100)), _.map(_.length))
      },
      test("listOf generates sizes in range") {
        checkSample(Gen.listOf(smallInt))(
          forall(isGreaterThanEqualTo(0) && isLessThanEqualTo(100)),
          _.map(_.length)
        )
      },
      test("listOf1 generates nonempty lists") {
        checkSample(Gen.listOf1(smallInt), size = 0)(forall(isNonEmpty))
      },
      test("listOfBounded generates lists whose size is in bounds") {
        checkSample(Gen.listOfBounded(2, 10)(smallInt))(forall(hasSize(isWithin(2, 10))))
      },
      test("listOfN generates lists of correct size") {
        checkSample(Gen.listOfN(10)(smallInt))(forall(equalTo(10)), _.map(_.length))
      },
      test("localDateTime generates values in range") {
        val min = LocalDateTime.ofEpochSecond(-238756L, 987435, ZoneOffset.ofHours(12))
        val max = LocalDateTime.ofEpochSecond(3987384759834L, 4736, ZoneOffset.ofHours(-2))
        checkSample(Gen.localDateTime(min, max))(forall(isGreaterThanEqualTo(min) && isLessThanEqualTo(max)))
      },
      test("long generates values in range") {
        val min = -775050485969923566L
        val max = 2826409893363053690L
        checkSample(Gen.long(min, max))(forall(isGreaterThanEqualTo(min) && isLessThanEqualTo(max)))
      },
      test("mapZIO maps an effectual function over a generator") {
        val gen = Gen.int(1, 6).mapZIO(n => ZIO.succeed(n + 6))
        checkSample(gen)(forall(Assertion.isGreaterThanEqualTo(7) && isLessThanEqualTo(12)))
      },
      test("mapOf generates sizes in range") {
        checkSample(Gen.mapOf(smallInt, smallInt))(forall(hasSize(isGreaterThanEqualTo(0) && isLessThanEqualTo(100))))
      },
      test("mapOf1 generates nonempty maps") {
        checkSample(Gen.mapOf1(smallInt, smallInt), size = 0)(forall(isNonEmpty))
      },
      test("mapOfBounded generates maps whose size is in bounds") {
        checkSample(Gen.mapOfBounded(2, 10)(smallInt, smallInt))(forall(hasSize(isWithin(2, 10))))
      },
      test("mapOfN generates maps of correct size") {
        checkSample(Gen.mapOfN(10)(smallInt, smallInt))(forall(hasSize(equalTo(10))))
      },
      test("medium generates sizes in range") {
        val gen = Gen.medium(Gen.listOfN(_)(Gen.int(-10, 10)))
        checkSample(gen)(forall(isGreaterThanEqualTo(0) && isLessThanEqualTo(100)), _.map(_.length))
      },
      test("none generates the constant empty value") {
        checkSample(Gen.none)(forall(isNone))
      },
      test("offsetDateTime generates values in range") {
        val min = OffsetDateTime.ofInstant(Instant.ofEpochSecond(-98345983298736L, 34334), ZoneOffset.ofHours(7))
        val max = OffsetDateTime.ofInstant(Instant.ofEpochSecond(39847530948982L, 4875384), ZoneOffset.ofHours(3))
        checkSample(Gen.offsetDateTime(min, max))(forall(isGreaterThanEqualTo(min) && isLessThanEqualTo(max)))
      },
      test("optionOf generates optional values") {
        checkSample(Gen.option(smallInt))(exists(isNone) && exists(isSome(anything)))
      },
      test("partialFunction generates partial functions") {
        val gen = for {
          f <- Gen.partialFunction[Has[Random], String, Int](Gen.int(-10, 10))
          s <- Gen.string(Gen.anyChar)
        } yield f.lift(s)
        checkSample(gen)(exists(isNone) && exists(isSome(anything)))
      },
      test("printableChar generates values in range") {
        checkSample(Gen.printableChar)(
          forall(isGreaterThanEqualTo(33) && isLessThanEqualTo(126)),
          _.map(_.toInt)
        )
      },
      test("setOf generates sizes in range") {
        checkSample(Gen.setOf(smallInt))(forall(hasSize(isGreaterThanEqualTo(0) && isLessThanEqualTo(100))))
      },
      test("setOf1 generates nonempty sets") {
        checkSample(Gen.setOf1(smallInt), size = 0)(forall(isNonEmpty))
      },
      test("setOfBounded generates sets whose size is in bounds") {
        checkSample(Gen.setOfBounded(2, 10)(smallInt))(forall(hasSize(isWithin(2, 10))))
      },
      test("setOfN generates sets of correct size") {
        checkSample(Gen.setOfN(10)(smallInt))(forall(hasSize(equalTo(10))))
      },
      test("short generates values in range") {
        checkSample(Gen.short(5, 10))(forall(isGreaterThanEqualTo(5) && isLessThanEqualTo(10)), _.map(_.toInt))
      },
      test("sized accesses size in environment") {
        checkSample(Gen.sized(Gen.const(_)), size = 50)(forall(equalTo(50)))
      } @@ nonFlaky,
      test("small generates sizes in range") {
        val gen = Gen.small(Gen.listOfN(_)(Gen.int(-10, 10)))
        checkSample(gen)(forall(isGreaterThanEqualTo(0) && isLessThanEqualTo(100)), _.map(_.length))
      },
      test("string generates sizes in range") {
        checkSample(Gen.string(Gen.printableChar))(
          forall(isGreaterThanEqualTo(0) && isLessThanEqualTo(100)),
          _.map(_.length)
        )
      },
      test("string1 generates nonempty strings") {
        checkSample(Gen.string1(Gen.printableChar), size = 0)(forall(isFalse), _.map(_.isEmpty))
      },
      test("stringBounded generates strings whose size is in bounds") {
        checkSample(Gen.stringBounded(2, 10)(Gen.printableChar))(forall(hasSizeString(isWithin(2, 10))))
      },
      test("stringN generates strings of correct size") {
        checkSample(Gen.stringN(10)(Gen.printableChar))(forall(equalTo(10)), _.map(_.length))
      },
      test("uniform generates values between 0 and 1") {
        checkSample(Gen.uniform)(forall(isGreaterThanEqualTo(0.0) && isLessThanEqualTo(1.0)))
      },
      test("unit generates the constant unit value") {
        checkSample(Gen.unit)(forall(equalTo(())))
      },
      test("vectorOf generates sizes in range") {
        checkSample(Gen.vectorOf(smallInt))(
          forall(isGreaterThanEqualTo(0) && isLessThanEqualTo(100)),
          _.map(_.length)
        )
      },
      test("vectorOfBounded generates vectors whose size is in bounds") {
        checkSample(Gen.vectorOfBounded(2, 10)(smallInt))(forall(hasSize(isWithin(2, 10))))
      },
      test("vectorOf1 generates nonempty vectors") {
        checkSample(Gen.vectorOf1(smallInt), size = 0)(forall(isNonEmpty))
      },
      test("vectorOfN generates vectors of correct size") {
        checkSample(Gen.vectorOfN(10)(smallInt))(forall(equalTo(10)), _.map(_.length))
      },
      test("weighted generates weighted distribution") {
        val weighted = Gen.weighted((Gen.const(true), 10), (Gen.const(false), 90))
        checkSample(weighted)(isTrue, ps => ps.count(!_) > ps.count(identity))
      },
      test("weighted never chooses a generator with zero probability") {
        val weighted = Gen.weighted((Gen.const(true), 1), (Gen.const(false), 0))
        checkSample(weighted)(isTrue, ps => ps.forall(identity))
      }
    ),
    suite("shrinks")(
      test("alphaNumericChar shrinks to zero") {
        checkShrink(Gen.alphaNumericChar)('0')
      },
      test("alphaNumericString shrinks to empty string") {
        checkShrink(Gen.alphaNumericString)("")
      },
      test("alphaNumericStringBounded shrinks to bottom of range") {
        checkShrink(Gen.alphaNumericStringBounded(2, 10))("00")
      },
      test("anyByte shrinks to zero") {
        checkShrink(Gen.anyByte)(0)
      },
      test("anyChar shrinks to zero") {
        checkShrink(Gen.anyChar)(0)
      },
      test("anyDayOfWeek shrinks to DayOfWeek.MONDAY") {
        checkShrink(Gen.anyDayOfWeek)(DayOfWeek.MONDAY)
      },
      test("anyFiniteDuration shrinks to Duration.Zero") {
        checkShrink(Gen.anyFiniteDuration)(Duration.Zero)
      },
      test("anyFloat shrinks to zero") {
        checkShrink(Gen.anyFloat)(0)
      },
      test("anyInstant shrinks to Instant.MIN") {
        val min = Instant.ofEpochSecond(-93487534873L, 2387642L)
        val max = Instant.ofEpochSecond(394876L, 376542888L)
        checkShrink(Gen.instant(min, max))(min)
      },
      test("anyLocalDateTime shrinks to LocalDateTime.MIN") {
        checkShrink(Gen.anyLocalDateTime)(LocalDateTime.MIN)
      },
      test("anyInt shrinks to zero") {
        checkShrink(Gen.anyInt)(0)
      },
      test("anyLong shrinks to zero") {
        checkShrink(Gen.anyLong)(0)
      },
      test("anyLocalDate shrinks to LocalDate.MIN") {
        checkShrink(Gen.anyLocalDate)(LocalDate.MIN)
      },
      test("anyLocalTime shrinks to LocalTime.MIN") {
        checkShrink(Gen.anyLocalTime)(LocalTime.MIN)
      },
      test("anyMonth shrinks to Month.JANUARY") {
        checkShrink(Gen.anyMonth)(Month.JANUARY)
      },
      test("anyMonthDay shrinks to MonthDay.of(Month.JANUARY, 1)") {
        checkShrink(Gen.anyMonthDay)(MonthDay.of(Month.JANUARY, 1))
      },
      test("anyOffsetDateTime shrinks to OffsetDateTime.MIN") {
        checkShrink(Gen.anyOffsetDateTime)(OffsetDateTime.MIN)
      },
      test("anyOffsetTime shrinks to OffsetTime.MIN") {
        checkShrink(Gen.anyOffsetTime)(OffsetTime.MIN)
      },
      test("anyPeriod shrinks to Period.ZERO") {
        checkShrink(Gen.anyPeriod)(Period.ZERO)
      },
      test("anyShort shrinks to zero") {
        checkShrink(Gen.anyShort)(0)
      },
      test("anyString shrinks to empty string") {
        checkShrink(Gen.anyString)("")
      },
      test("anyUnicodeChar shrinks to zero") {
        checkShrink(Gen.anyUnicodeChar)(0)
      },
      test("anyYear shrinks to Year.MIN_VALUE") {
        checkShrink(Gen.anyYear)(Year.of(Year.MIN_VALUE))
      },
      test("anyYearMonth shrinks to YearMonth.of(Year.MIN_VALUE, Month.JANUARY)") {
        checkShrink(Gen.anyYearMonth)(YearMonth.of(Year.MIN_VALUE, Month.JANUARY))
      },
      test("anyZoneOffset shrinks to ZoneOffset.MIN") {
        checkShrink(Gen.anyZoneOffset)(ZoneOffset.MIN)
      },
      test("boolean shrinks to false") {
        checkShrink(Gen.boolean)(false)
      },
      test("byte shrinks to bottom of range") {
        checkShrink(Gen.byte(38, 123))(38)
      },
      test("char shrinks to bottom of range") {
        checkShrink(Gen.char(33, 123))(33)
      },
      test("chunkOf shrinks to empty vector") {
        checkShrink(Gen.chunkOf(smallInt))(Chunk.empty)
      },
      test("chunkOf1 shrinks to singleton vector") {
        checkShrink(Gen.chunkOf1(smallInt))(NonEmptyChunk(-10))
      },
      test("chunkOfBounded shrinks to bottom of range") {
        checkShrink(Gen.chunkOfBounded(2, 10)(smallInt))(Chunk(-10, -10))
      },
      test("chunkOfN shrinks elements") {
        checkShrink(Gen.chunkOfN(10)(smallInt))(Chunk.fill(10)(-10))
      },
      test("collect collects shrinks a partial function is defined at") {
        checkShrink(Gen.int(1, 10).collect { case n if n % 2 == 0 => n })(2)
      },
      test("double shrinks to bottom of range") {
        checkShrink(Gen.double(5.0, 9.0))(5.0)
      },
      test("either shrinks to left") {
        checkShrink(Gen.either(smallInt, smallInt))(Left(-10))
      },
      test("exponential shrinks to zero") {
        checkShrink(Gen.exponential)(0.0)
      },
      test("filter filters shrinks according to predicate") {
        checkShrink(Gen.int(1, 10).filter(_ % 2 == 0))(2)
      },
      test("finiteDuration shrinks to min") {
        val min = 97.minutes + 13.seconds + 32.nanos
        val max = 3.hours + 2.minutes + 45.seconds + 23453.nanos
        checkShrink(Gen.finiteDuration(min, max))(min)
      },
      test("instant shrinks to min") {
        val min = Instant.ofEpochSecond(-93487534873L, 2387642L)
        val max = Instant.ofEpochSecond(394876L, 376542888L)
        checkShrink(Gen.instant(min, max))(min)
      },
      test("int shrinks to bottom of range") {
        checkShrink(smallInt)(-10)
      },
      test("listOf shrinks to empty list") {
        checkShrink(Gen.listOf(smallInt))(Nil)
      },
      test("listOf1 shrinks to singleton list") {
        checkShrink(Gen.listOf1(smallInt))(::(-10, Nil))
      },
      test("listOfBounded shrinks to bottom of range") {
        checkShrink(Gen.listOfBounded(2, 10)(smallInt))(List(-10, -10))
      },
      test("listOfN shrinks elements") {
        checkShrink(Gen.listOfN(10)(smallInt))(List.fill(10)(-10))
      },
      test("localDateTime shrinks to min") {
        val min = LocalDateTime.ofEpochSecond(-349875349L, 38743843, ZoneOffset.ofHours(-13))
        val max = LocalDateTime.ofEpochSecond(-234234L, 34985434, ZoneOffset.ofHours(-1))
        checkShrink(Gen.localDateTime(min, max))(min)
      },
      test("long shrinks to bottom of range") {
        val min = -8649088475068069159L
        val max = 7907688119669724678L
        checkShrink(Gen.long(min, max))(min)
      },
      test("mapOf shrinks to empty map") {
        checkShrink(Gen.mapOf(smallInt, smallInt))(Map.empty)
      },
      test("mapOf1 shrinks to singleton map") {
        checkShrink(Gen.mapOf1(smallInt, smallInt))(Map(-10 -> -10))
      },
      test("mapOfBounded shrinks to bottom of range") {
        checkShrink(Gen.mapOfBounded(1, 10)(smallInt, smallInt))(Map(-10 -> -10))
      },
      test("mapOfN shrinks elements") {
        checkShrink(Gen.mapOfN(1)(smallInt, smallInt))(Map(-10 -> -10))
      },
      test("noShrink discards the shrinker for this generator") {
        assertM(shrinks(Gen.anyInt.noShrink))(hasSize(equalTo(1)))
      },
      test("offsetDateTime shrinks to min") {
        val min = OffsetDateTime.ofInstant(Instant.ofEpochSecond(8345983298736L, 345), ZoneOffset.ofHours(-4))
        val max = OffsetDateTime.ofInstant(Instant.ofEpochSecond(348975394875348L, 56456456), ZoneOffset.ofHours(0))
        checkShrink(Gen.offsetDateTime(min, max))(min)
      },
      test("optionOf shrinks to None") {
        checkShrink(Gen.option(smallInt))(None)
      },
      test("printableChar shrinks to bottom of range") {
        checkShrink(Gen.printableChar)('!')
      },
      test("reshrink applies new shrinking logic") {
        val gen = Gen.int(0, 10).reshrink(Sample.shrinkIntegral(10))
        checkShrink(gen)(10)
      },
      test("setOf shrinks to empty set") {
        checkShrink(Gen.setOf(smallInt))(Set.empty)
      },
      test("setOf1 shrinks to singleton set") {
        checkShrink(Gen.setOf1(smallInt))(Set(-10))
      },
      test("setOfBounded shrinks to bottom of range") {
        checkShrink(Gen.setOfBounded(1, 10)(smallInt))(Set(-10))
      },
      test("setOfN shrinks elements") {
        checkShrink(Gen.setOfN(1)(smallInt))(Set(-10))
      },
      test("short shrinks to bottom of range") {
        checkShrink(Gen.short(5, 10))(5)
      },
      test("some shrinks to smallest value") {
        checkShrink(Gen.some(smallInt))(Some(-10))
      },
      test("string shrinks to empty string") {
        checkShrink(Gen.string(Gen.printableChar))("")
      },
      test("string1 shrinks to single character") {
        checkShrink(Gen.string1(Gen.printableChar))("!")
      },
      test("stringBounded shrinks to bottom of range") {
        checkShrink(Gen.stringBounded(2, 10)(Gen.printableChar))("!!")
      },
      test("stringN shrinks characters") {
        checkShrink(Gen.stringN(10)(Gen.printableChar))("!!!!!!!!!!")
      },
      test("uniform shrinks to zero") {
        checkShrink(Gen.uniform)(0.0)
      },
      test("vectorOf shrinks to empty vector") {
        checkShrink(Gen.vectorOf(smallInt))(Vector.empty)
      },
      test("vectorOf1 shrinks to singleton vector") {
        checkShrink(Gen.vectorOf1(smallInt))(Vector(-10))
      },
      test("vectorOfBounded shrinks to bottom of range") {
        checkShrink(Gen.vectorOfBounded(2, 10)(smallInt))(Vector(-10, -10))
      },
      test("vectorOfN shrinks elements") {
        checkShrink(Gen.vectorOfN(10)(smallInt))(Vector.fill(10)(-10))
      },
      test("zip shrinks correctly") {
        checkShrink(three <*> three)((0, 0))
      },
      test("zipWith shrinks correctly") {
        checkShrink(smallInt.zipWith(smallInt)(_ + _))(-20)
      }
    ),
    suite("zipWith")(
      test("left preservation") {
        checkM(deterministic, deterministic) { (a, b) =>
          for {
            left  <- sample(a.zip(b).map(_._1))
            right <- sample(a)
          } yield assert(left)(startsWith(right))
        }
      } @@ scala2Only,
      test("right preservation") {
        checkM(deterministic, deterministic) { (a, b) =>
          for {
            left  <- sample(a.zip(b).map(_._2))
            right <- sample(b)
          } yield assert(left)(startsWith(right))
        }
      } @@ scala2Only,
      test("shrinking") {
        checkM(random, random) { (a, b) =>
          for {
            left  <- shrink(a.zip(b))
            right <- shrink(a.cross(b))
          } yield assert(left)(equalTo(right))
        }
      },
      test("shrink search") {
        val gen      = shrinkable.zip(shrinkable)
        val smallInt = Gen.int(0, 9)
        checkM(smallInt, smallInt) { (m, n) =>
          for {
            result <- shrinkWith(gen) { case (x, y) => x < m && y < n }
          } yield assert(result.reverse.headOption)(isSome(equalTo((m, 0)) || equalTo((0, n))))
        }
      },
      test("determinism") {
        val gen = Gen.anyInt <&> Gen.anyInt
        assertM(gen.runHead)(isSome(equalTo((-1170105035, 234785527))))
      } @@ setSeed(42) @@ nonFlaky
    ),
    test("fromIterable constructs deterministic generators") {
      val expected   = List.range(1, 6).flatMap(x => List.range(1, 6).map(y => x + y))
      val exhaustive = Gen.fromIterable(1 until 6)
      val actual     = exhaustive.crossWith(exhaustive)(_ + _)
      checkFinite(actual)(equalTo(expected))
    } @@ scala2Only, //todo fix when #2232 is resolved
    test("size can be modified locally") {
      val getSize = Gen.size.sample.map(_.value).runCollect.map(_.head)
      val result = for {
        x <- Sized.withSize(200)(getSize)
        y <- getSize
      } yield x == 2 * y
      assertM(provideSize(result)(100))(isTrue)
    },
    test("suspend lazily constructs a generator") {
      check(genIntList)(as => assert(as.reverse.reverse)(equalTo(as)))
    },
    test("runCollect") {
      val domain = List.range(-10, 10)
      val gen    = Gen.fromIterable(domain)
      for {
        a <- gen.runCollect
        b <- gen.runCollect
      } yield assert(a)(equalTo(domain)) &&
        assert(b)(equalTo(domain))
    } @@ scala2Only,
    test("runCollectN") {
      val gen = Gen.int(-10, 10)
      for {
        a <- gen.runCollectN(100)
        b <- gen.runCollectN(100)
      } yield assert(a)(not(equalTo(b))) &&
        assert(a)(hasSize(equalTo(100))) &&
        assert(b)(hasSize(equalTo(100)))
    },
    test("runHead") {
      assertM(Gen.int(-10, 10).runHead)(isSome(isWithin(-10, 10)))
    },
    test("crossAll") {
      val gen = Gen.crossAll(
        List(
          Gen.fromIterable(List(1, 2)),
          Gen.fromIterable(List(3)),
          Gen.fromIterable(List(4, 5))
        )
      )
      assertM(gen.runCollect)(
        equalTo(
          List(
            List(1, 3, 4),
            List(1, 3, 5),
            List(2, 3, 4),
            List(2, 3, 5)
          )
        )
      )
    },
    test("zipAll") {
      val gen = Gen.zipAll(
        List(
          Gen.fromIterable(List(1, 2)),
          Gen.fromIterable(List(3)),
          Gen.fromIterable(List(4, 5))
        )
      )
      assertM(gen.runCollect)(
        equalTo(
          List(
            List(1, 3, 4),
            List(2, 3, 5)
          )
        )
      )
    },
    test("unfoldGen") {
      sealed trait Command
      case object Pop                   extends Command
      final case class Push(value: Int) extends Command

      val genPop: Gen[Any, Command]          = Gen.const(Pop)
      def genPush: Gen[Has[Random], Command] = Gen.anyInt.map(value => Push(value))

      val genCommands: Gen[Has[Random] with Has[Sized], List[Command]] =
        Gen.unfoldGen(0) { n =>
          if (n <= 0)
            genPush.map(command => (n + 1, command))
          else
            Gen.oneOf(
              genPop.map(command => (n - 1, command)),
              genPush.map(command => (n + 1, command))
            )
        }

      check(genCommands) { commands =>
        val stack = scala.collection.mutable.Stack.empty[Int]
        commands.foreach {
          case Pop         => stack.pop()
          case Push(value) => stack.push(value)
        }
        assertCompletes
      }
    }
  )
}
