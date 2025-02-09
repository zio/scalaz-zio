package zio.test.internal

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio._
import zio.internal.ansi.AnsiStringOps
import zio.test.diff.{Diff, DiffResult}

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}
import zio.test.{ErrorMessage => M, SmartAssertionOps => _, _}

object SmartAssertions {

  val anything: TestArrow[Any, Boolean] =
    TestArrow.make[Any, Boolean](_ => TestTrace.boolean(true)(M.was + "anything"))

  def approximatelyEquals[A: Numeric](reference: A, tolerance: A): TestArrow[A, Boolean] =
    TestArrow.make[A, Boolean] { actual =>
      val referenceType = implicitly[Numeric[A]]
      val max           = referenceType.plus(reference, tolerance)
      val min           = referenceType.minus(reference, tolerance)

      val result = referenceType.gteq(actual, min) && referenceType.lteq(actual, max)
      TestTrace.boolean(result) {
        M.pretty(actual) + M.did + "approximately equal" + M.pretty(reference) + "with a tolerance of" +
          M.pretty(tolerance)
      }
    }

  def custom[A, B](customAssertion: CustomAssertion[A, B]): TestArrow[A, B] =
    TestArrow.make { a =>
      customAssertion.run(a) match {
        case Left(error)  => TestTrace.fail(error)
        case Right(value) => TestTrace.succeed(value)
      }
    }

  def isSome[A]: TestArrow[Option[A], A] =
    TestArrow
      .make[Option[A], A] {
        case Some(value) => TestTrace.succeed(value)
        case None        => TestTrace.fail("Option was None")
      }

  def asTrySuccess[A]: TestArrow[Try[A], A] =
    TestArrow
      .make[Try[A], A] {
        case Failure(_)     => TestTrace.fail("Try was Failure")
        case Success(value) => TestTrace.succeed(value)
      }

  def asTryFailure[A]: TestArrow[Try[A], Throwable] =
    TestArrow
      .make[Try[A], Throwable] {
        case Failure(exception) => TestTrace.succeed(exception)
        case Success(_)         => TestTrace.fail("Try was Success")
      }

  def asRight[A]: TestArrow[Either[_, A], A] =
    TestArrow
      .make[Either[_, A], A] {
        case Right(value) => TestTrace.succeed(value)
        case Left(_)      => TestTrace.fail("Either was Left")
      }

  def asLeft[A]: TestArrow[Either[A, _], A] =
    TestArrow
      .make[Either[A, _], A] {
        case Left(value) => TestTrace.succeed(value)
        case Right(_)    => TestTrace.fail("Either was Right")
      }

  def isEmptyIterable[A]: TestArrow[Iterable[A], Boolean] =
    TestArrow
      .make[Iterable[A], Boolean] { as =>
        TestTrace.boolean(as.isEmpty) {
          className(as) + M.was + "empty" + M.text(s"(size ${as.size})")
        }
      }

  def isEmptyString: TestArrow[String, Boolean] =
    TestArrow
      .make[String, Boolean] { string =>
        TestTrace.boolean(string.isEmpty) {
          M.pretty(string) + M.was + "empty" + M.text(s"(length ${string.length})")
        }
      }

  def isNonEmptyIterable: TestArrow[Iterable[Any], Boolean] =
    TestArrow
      .make[Iterable[Any], Boolean] { as =>
        TestTrace.boolean(as.nonEmpty) {
          className(as) + M.choice("was not", "was") + "empty"
        }
      }

  def isNonEmptyString: TestArrow[String, Boolean] =
    TestArrow
      .make[String, Boolean] { string =>
        TestTrace.boolean(string.nonEmpty) {
          M.pretty(string) + M.choice("was not", "was") + "empty"
        }
      }

  def isEmptyOption: TestArrow[Option[Any], Boolean] =
    TestArrow
      .make[Option[Any], Boolean] { option =>
        TestTrace.boolean(option.isEmpty) {
          className(option) + M.was + "empty"
        }
      }

  def isDefinedOption: TestArrow[Option[Any], Boolean] =
    TestArrow
      .make[Option[Any], Boolean] { option =>
        TestTrace.boolean(option.isDefined) {
          className(option) + M.was + "defined"
        }
      }

  def forallIterable[A](predicate: TestArrow[A, Boolean]): TestArrow[Iterable[A], Boolean] =
    TestArrow
      .make[Iterable[A], Boolean] { seq =>
        val results = seq.map(a => TestArrow.run(predicate, Right(a)))

        val failures = results.filter(_.isFailure)
        val elements = if (failures.size == 1) "element" else "elements"

        TestTrace.Node(
          Result.succeed(failures.isEmpty),
          M.pretty(failures.size) + M.text(s"$elements failed the predicate"),
          children = if (failures.isEmpty) None else Some(failures.reduce(_ && _))
        )
      }

  def existsIterable[A](predicate: TestArrow[A, Boolean]): TestArrow[Iterable[A], Boolean] =
    TestArrow
      .make[Iterable[A], Boolean] { seq =>
        val result = seq.view.map(a => TestArrow.run(predicate, Right(a))).find(tt => tt.isSuccess || tt.isDie)

        val success  = result.filter(_.isSuccess)
        val elements = if (success.nonEmpty) "element" else "elements"

        val elementsSatisfiedPredicateMsg =
          if (result.exists(_.isDie))
            M.text(s"$elements satisfied the predicate before it threw an exception")
          else
            M.text(s"$elements satisfied the predicate")

        TestTrace.Node(
          Result.succeed(success.nonEmpty),
          M.pretty(success.size) + elementsSatisfiedPredicateMsg,
          children = result
        )
      }

  def containsSeq[A, B >: A](value: A): TestArrow[Seq[B], Boolean] =
    TestArrow
      .make[Seq[B], Boolean] { seq =>
        TestTrace.boolean(seq.contains(value)) {
          className(seq) + M.did + "contain" + M.pretty(value)
        }
      }

  def containsIterable[A](value: A): TestArrow[Iterable[A], Boolean] =
    TestArrow
      .make[Iterable[A], Boolean] { seq =>
        TestTrace.boolean(seq.exists(_ == value)) {
          className(seq) + M.did + "contain" + M.pretty(value)
        }
      }

  def containsCause[E](expected: Cause[E]): TestArrow[Cause[E], Boolean] =
    TestArrow
      .make[Cause[E], Boolean] { cause =>
        TestTrace.boolean(cause.contains(expected)) {
          M.pretty(cause) + M.did + "contain" + M.pretty(expected)
        }
      }

  def containsOption[A](value: A): TestArrow[Option[A], Boolean] =
    TestArrow
      .make[Option[A], Boolean] { option =>
        TestTrace.boolean(option.contains(value)) {
          className(option) + M.did + "contain" + M.pretty(value)
        }
      }

  def containsString(value: String): TestArrow[String, Boolean] =
    TestArrow
      .make[String, Boolean] { str =>
        TestTrace.boolean(str.contains(value)) {
          M.pretty(str) + M.did + "contain" + M.pretty(value)
        }
      }

  def hasAt[A](index: Int): TestArrow[Seq[A], A] =
    TestArrow
      .make[Seq[A], A] { as =>
        Try(as(index)).toOption match {
          case Some(value) => TestTrace.succeed(value)
          case None =>
            TestTrace.fail(
              M.text("Invalid index") + M.value(index) + "for" + className(as) + "of size" + M.value(as.length)
            )
        }
      }

  def hasField[A, B](name: String, proj: A => B): TestArrow[A, B] =
    TestArrow
      .make[A, B] { a =>
        TestTrace.succeed(proj(a))
      }

  def hasKey[K, V](key: K): TestArrow[Map[K, V], V] =
    TestArrow
      .make[Map[K, V], V] { mapKV =>
        Try(mapKV(key)).toOption match {
          case Some(value) => TestTrace.succeed(value)
          case None =>
            TestTrace.fail(
              M.text("Missing key") + M.pretty(key)
            )
        }
      }

  def head[A]: TestArrow[Iterable[A], A] =
    TestArrow
      .make[Iterable[A], A] { as =>
        as.headOption match {
          case Some(value) => TestTrace.succeed(value)
          case None =>
            TestTrace.fail(className(as) + "was empty")
        }
      }

  def last[A]: TestArrow[Iterable[A], A] =
    TestArrow
      .make[Iterable[A], A] { as =>
        as.lastOption match {
          case Some(value) => TestTrace.succeed(value)
          case None        => TestTrace.fail(className(as) + "was empty")
        }
      }

  def isEven[A](implicit integral: Integral[A]): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { (a: A) =>
        TestTrace.boolean(integral.rem(a, integral.fromInt(2)) == integral.fromInt(0)) {
          M.pretty(a) + M.was + "even"
        }
      }

  def isOdd[A](implicit integral: Integral[A]): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { (a: A) =>
        TestTrace.boolean(integral.rem(a, integral.fromInt(2)) == integral.fromInt(1)) {
          M.pretty(a) + M.was + "odd"
        }
      }

  def greaterThan[A](that: A)(implicit ordering: Ordering[A]): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { (a: A) =>
        TestTrace.boolean(ordering.gt(a, that)) {
          M.pretty(a) + M.was + "greater than" + M.pretty(that)
        }
      }

  def greaterThanOrEqualTo[A](that: A)(implicit ordering: Ordering[A]): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.gteq(a, that)) {
          M.pretty(a) + M.was + s"greater than or equal to" + M.pretty(that)
        }
      }

  def lessThan[A](that: A)(implicit ordering: Ordering[A]): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.lt(a, that)) {
          M.pretty(a) + M.was + "less than" + M.pretty(that)
        }
      }

  def lessThanOrEqualTo[A](that: A)(implicit ordering: Ordering[A]): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.lteq(a, that)) {
          M.pretty(a) + M.was + "less than or equal to" + M.pretty(that)
        }
      }

  def greaterThanL[A, B](that: B)(implicit ordering: Ordering[B], conv: (A => B)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { (a: A) =>
        TestTrace.boolean(ordering.gt(conv(a), that)) {
          M.pretty(a) + M.was + "greater than" + M.pretty(that)
        }
      }

  def greaterThanOrEqualToL[A, B](that: B)(implicit ordering: Ordering[B], conv: (A => B)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.gteq(conv(a), that)) {
          M.pretty(a) + M.was + s"greater than or equal to" + M.pretty(that)
        }
      }

  def lessThanL[A, B](that: B)(implicit ordering: Ordering[B], conv: (A => B)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.lt(conv(a), that)) {
          M.pretty(a) + M.was + "less than" + M.pretty(that)
        }
      }

  def lessThanOrEqualToL[A, B](that: B)(implicit ordering: Ordering[B], conv: (A => B)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.lteq(conv(a), that)) {
          M.pretty(a) + M.was + "less than or equal to" + M.pretty(that)
        }
      }

  def greaterThanR[A, B](that: B)(implicit ordering: Ordering[A], conv: (B => A)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { (a: A) =>
        TestTrace.boolean(ordering.gt(a, conv(that))) {
          M.pretty(a) + M.was + "greater than" + M.pretty(that)
        }
      }

  def greaterThanOrEqualToR[A, B](that: B)(implicit ordering: Ordering[A], conv: (B => A)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.gteq(a, conv(that))) {
          M.pretty(a) + M.was + s"greater than or equal to" + M.pretty(that)
        }
      }

  def lessThanR[A, B](that: B)(implicit ordering: Ordering[A], conv: (B => A)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.lt(a, conv(that))) {
          M.pretty(a) + M.was + "less than" + M.pretty(that)
        }
      }

  def lessThanOrEqualToR[A, B](that: B)(implicit ordering: Ordering[A], conv: (B => A)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(ordering.lteq(a, conv(that))) {
          M.pretty(a) + M.was + "less than or equal to" + M.pretty(that)
        }
      }

  def equalTo[A](that: A)(implicit diff: OptionalImplicit[Diff[A]]): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        val result = (a, that) match {
          case (a: Array[_], that: Array[_]) => a.sameElements[Any](that)
          case _                             => a == that
        }

        TestTrace.boolean(result) {
          diff.value match {
            case Some(diff) if !diff.isLowPriority && !result =>
              val diffResult = diff.diff(that, a)
              diffResult match {
                case DiffResult.Different(_, _, None) =>
                  M.pretty(a) + M.equals + M.pretty(that)
                case diffResult =>
                  M.choice("There was no difference", "There was a difference") ++
                    M.custom(ConsoleUtils.underlined("Expected")) ++ M.custom(PrettyPrint(that)) ++
                    M.custom(
                      ConsoleUtils.underlined(
                        "Diff"
                      ) + s" ${scala.Console.RED}-expected ${scala.Console.GREEN}+obtained".faint
                    ) ++
                    M.custom(scala.Console.RESET + diffResult.render)
              }
            case _ =>
              M.pretty(a) + M.equals + M.pretty(that)
          }
        }
      }

  def equalToL[A, B](that: B)(implicit diff: OptionalImplicit[Diff[B]], conv: (A => B)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        val result = (a, that) match {
          case (a: Array[_], that: Array[_]) => a.sameElements[Any](that)
          case _                             => a == that
        }

        TestTrace.boolean(result) {
          diff.value match {
            case Some(diff) if !diff.isLowPriority && !result =>
              val diffResult = diff.diff(that, conv(a))
              diffResult match {
                case DiffResult.Different(_, _, None) =>
                  M.pretty(a) + M.equals + M.pretty(that)
                case diffResult =>
                  M.choice("There was no difference", "There was a difference") ++
                    M.custom(ConsoleUtils.underlined("Expected")) ++ M.custom(PrettyPrint(that)) ++
                    M.custom(
                      ConsoleUtils.underlined(
                        "Diff"
                      ) + s" ${scala.Console.RED}-expected ${scala.Console.GREEN}+obtained".faint
                    ) ++
                    M.custom(scala.Console.RESET + diffResult.render)
              }
            case _ =>
              M.pretty(a) + M.equals + M.pretty(that)
          }
        }
      }

  def equalToR[A, B](that: B)(implicit diff: OptionalImplicit[Diff[A]], conv: (B => A)): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        val result = (a, that) match {
          case (a: Array[_], that: Array[_]) => a.sameElements[Any](that)
          case _                             => a == that
        }

        TestTrace.boolean(result) {
          diff.value match {
            case Some(diff) if !diff.isLowPriority && !result =>
              val diffResult = diff.diff(conv(that), a)
              diffResult match {
                case DiffResult.Different(_, _, None) =>
                  M.pretty(a) + M.equals + M.pretty(that)
                case diffResult =>
                  M.choice("There was no difference", "There was a difference") ++
                    M.custom(ConsoleUtils.underlined("Expected")) ++ M.custom(PrettyPrint(that)) ++
                    M.custom(
                      ConsoleUtils.underlined(
                        "Diff"
                      ) + s" ${scala.Console.RED}-expected ${scala.Console.GREEN}+obtained".faint
                    ) ++
                    M.custom(scala.Console.RESET + diffResult.render)
              }
            case _ =>
              M.pretty(a) + M.equals + M.pretty(that)
          }
        }
      }

  def asCauseDie[E]: TestArrow[Cause[E], Throwable] =
    TestArrow
      .make[Cause[E], Throwable] {
        case cause if cause.dieOption.isDefined =>
          TestTrace.succeed(cause.dieOption.get)
        case _ =>
          TestTrace.fail(M.value("Cause") + M.did + "contain a" + M.value("Die"))
      }

  def asCauseFailure[E]: TestArrow[Cause[E], E] =
    TestArrow
      .make[Cause[E], E] {
        case cause if cause.failureOption.isDefined =>
          TestTrace.succeed(cause.failureOption.get)
        case _ =>
          TestTrace.fail(M.value("Cause") + M.did + "contain a" + M.value("Fail"))
      }

  def asCauseInterrupted[E]: TestArrow[Cause[E], Boolean] =
    TestArrow
      .make[Cause[E], Boolean] {
        case cause if cause.isInterrupted =>
          TestTrace.succeed(true)
        case _ =>
          TestTrace.fail(M.value("Cause") + M.did + "contain a" + M.value("Interrupt"))
      }

  def asExitDie[E, A]: TestArrow[Exit[E, A], Throwable] =
    TestArrow
      .make[Exit[E, A], Throwable] {
        case Exit.Failure(cause) if cause.dieOption.isDefined =>
          TestTrace.succeed(cause.dieOption.get)
        case Exit.Success(_) =>
          TestTrace.fail(M.value("Exit.Success") + M.was + "a" + M.value("Cause.Die"))
        case Exit.Failure(_) =>
          TestTrace.fail(M.value("Exit.Failure") + M.did + "contain a" + M.value("Cause.Die"))
      }

  def asExitCause[E]: TestArrow[Exit[E, Any], Cause[E]] =
    TestArrow
      .make[Exit[E, Any], Cause[E]] {
        case Exit.Failure(cause) =>
          TestTrace.succeed(cause)
        case Exit.Success(_) =>
          TestTrace.fail(M.value("Exit.Success") + M.did + "contain a" + M.value("Cause"))
      }

  def asExitFailure[E]: TestArrow[Exit[E, Any], E] =
    TestArrow
      .make[Exit[E, Any], E] {
        case Exit.Failure(cause) if cause.failureOption.isDefined =>
          TestTrace.succeed(cause.failureOption.get)
        case Exit.Success(_) =>
          TestTrace.fail(M.value("Exit.Success") + M.was + "a" + M.value("Failure"))
        case Exit.Failure(_) =>
          TestTrace.fail(M.value("Exit.Failure") + M.did + "contain a" + M.value("Cause.Fail"))
      }

  def asExitInterrupted[E, A]: TestArrow[Exit[E, A], Boolean] =
    TestArrow
      .make[Exit[E, A], Boolean] {
        case Exit.Failure(cause) if cause.isInterrupted =>
          TestTrace.succeed(true)
        case Exit.Success(_) =>
          TestTrace.fail(M.value("Exit.Success") + M.was + "interrupted")
        case Exit.Failure(_) =>
          TestTrace.fail(M.value("Exit.Failure") + M.did + "contain a" + M.value("Cause.Interrupt"))
      }

  def asExitSuccess[E, A]: TestArrow[Exit[E, A], A] =
    TestArrow
      .make[Exit[E, A], A] {
        case Exit.Success(value) =>
          TestTrace.succeed(value)
        case Exit.Failure(_) =>
          TestTrace.fail(M.value("Exit") + M.was + "a" + M.value("Success"))
      }

  val throws: TestArrow[Any, Throwable] =
    TestArrow.makeEither(
      TestTrace.succeed,
      _ => TestTrace.fail("Expected failure")
    )

  def as[A, B](implicit CB: ClassTag[B]): TestArrow[A, B] =
    TestArrow
      .make[A, B] { a =>
        CB.unapply(a) match {
          case Some(value) => TestTrace.succeed(value)
          case None =>
            TestTrace.fail(M.value(a.getClass.getSimpleName) + "is not an instance of" + M.value(className(CB)))
        }
      }

  def is[A, B](implicit CB: ClassTag[B]): TestArrow[A, Boolean] =
    TestArrow
      .make[A, Boolean] { a =>
        TestTrace.boolean(CB.unapply(a).isDefined) {
          M.value(a.getClass.getSimpleName) + M.was + "an instance of" + M.value(className(CB))
        }
      }

  def startsWithSeq[A](prefix: Seq[A]): TestArrow[Seq[A], Boolean] =
    TestArrow
      .make[Seq[A], Boolean] { seq =>
        TestTrace.boolean(seq.startsWith(prefix)) {
          M.pretty(seq) + M.did + "start with" + M.pretty(prefix)
        }
      }

  def startsWithString(prefix: String): TestArrow[String, Boolean] =
    TestArrow
      .make[String, Boolean] { string =>
        TestTrace.boolean(string.startsWith(prefix)) {
          M.value(string) + M.did + "start with" + M.value(prefix)
        }
      }

  def endsWithSeq[A](postfix: Seq[A]): TestArrow[Seq[A], Boolean] =
    TestArrow
      .make[Seq[A], Boolean] { seq =>
        TestTrace.boolean(seq.endsWith(postfix)) {
          M.value(seq) + M.did + "end with" + M.value(seq)
        }
      }

  def endsWithString(postfix: String): TestArrow[String, Boolean] =
    TestArrow
      .make[String, Boolean] { string =>
        TestTrace.boolean(string.endsWith(postfix)) {
          M.value(string) + M.did + "end with" + M.value(postfix)
        }
      }

  private def className[A](C: ClassTag[A]): String =
    try {
      C.runtimeClass.getSimpleName
    } catch {
      // See https://github.com/scala/bug/issues/2034.
      case t: InternalError if t.getMessage == "Malformed class name" =>
        C.runtimeClass.getName
    }

  private def className[A](a: Iterable[A]) =
    M.value(a.toString.takeWhile(_ != '('))

  private def className[A](a: Option[A]) =
    M.value(a.toString.takeWhile(_ != '('))

  object Implicits {
    case class Converter[-A, +B](f: A => B)
    case class Converter2[-A, +B](f: A => B)

    implicit val byte: Converter[Byte, java.lang.Byte]         = Converter(x => x)
    implicit val short: Converter[Short, java.lang.Short]      = Converter(x => x)
    implicit val int: Converter[Int, java.lang.Integer]        = Converter(x => x)
    implicit val long: Converter[Long, java.lang.Long]         = Converter(x => x)
    implicit val float: Converter[Float, java.lang.Float]      = Converter(x => x)
    implicit val double: Converter[Double, java.lang.Double]   = Converter(x => x)
    implicit val byte2: Converter2[java.lang.Byte, Byte]       = Converter2(x => x)
    implicit val short2: Converter2[java.lang.Short, Short]    = Converter2(x => x)
    implicit val int2: Converter2[java.lang.Integer, Int]      = Converter2(x => x)
    implicit val long2: Converter2[java.lang.Long, Long]       = Converter2(x => x)
    implicit val float2: Converter2[java.lang.Float, Float]    = Converter2(x => x)
    implicit val double2: Converter2[java.lang.Double, Double] = Converter2(x => x)

    implicit def converter[A, B, C](implicit g: Converter[B, C], f: A => B): A => C =
      g.f.compose(f)

    implicit def converter2[A, B, C](implicit f: Converter2[A, B], g: B => C): A => C =
      g.compose(f.f)
  }

}
