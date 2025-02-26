package zio

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.collection.AbstractIterator

object StackTracesSpec extends ZIOBaseSpec {

  def spec = suite("StackTracesSpec")(
    suite("captureSimpleCause")(
      test("captures a simple failure") {
        for {
          _     <- ZIO.succeed(25)
          value  = ZIO.fail("Oh no!")
          cause <- value.cause
        } yield assert(cause)(causeHasTrace {
          """java.lang.String: Oh no!
            |	at zio.StackTracesSpec.spec.value
            |	at zio.StackTracesSpec.spec
            |""".stripMargin
        })
      }
    ),
    suite("captureMultiMethod")(
      test("captures a deep embedded failure") {
        val deepUnderlyingFailure =
          for {
            _ <- ZIO.succeed(5)
            f <- ZIO.fail("Oh no!").ensuring(ZIO.dieMessage("deep failure"))
          } yield f

        val underlyingFailure =
          for {
            _ <- ZIO.succeed(15)
            f <- deepUnderlyingFailure.ensuring(ZIO.dieMessage("other failure"))
          } yield f

        for {
          _     <- ZIO.succeed(25)
          value  = underlyingFailure
          cause <- value.cause
        } yield assert(cause)(causeHasTrace {
          """java.lang.String: Oh no!
            |	at zio.StackTracesSpec.spec.deepUnderlyingFailure
            |	at zio.StackTracesSpec.spec.underlyingFailure
            |	at zio.StackTracesSpec.spec
            |	Suppressed: java.lang.RuntimeException: deep failure
            |		at zio.StackTracesSpec.spec.deepUnderlyingFailure
            |		at zio.StackTracesSpec.spec.underlyingFailure
            |		at zio.StackTracesSpec.spec
            |		Suppressed: java.lang.RuntimeException: other failure
            |			at zio.StackTracesSpec.spec.underlyingFailure
            |			at zio.StackTracesSpec.spec
            |""".stripMargin
        })
      },
      test("captures a deep embedded failure without suppressing the underlying cause") {
        val deepUnderlyingFailure =
          for {
            _ <- ZIO.succeed(5)
            f <- ZIO.fail("Oh no!").ensuring(ZIO.dieMessage("deep failure"))
          } yield f

        val underlyingFailure =
          for {
            _ <- ZIO.succeed(15)
            f <- deepUnderlyingFailure
          } yield f

        for {
          _     <- ZIO.succeed(25)
          value  = underlyingFailure
          cause <- value.cause
        } yield assert(cause)(causeHasTrace {
          """java.lang.String: Oh no!
            |	at zio.StackTracesSpec.spec.deepUnderlyingFailure
            |	at zio.StackTracesSpec.spec.underlyingFailure
            |	at zio.StackTracesSpec.spec
            |	Suppressed: java.lang.RuntimeException: deep failure
            |		at zio.StackTracesSpec.spec.deepUnderlyingFailure
            |		at zio.StackTracesSpec.spec.underlyingFailure
            |		at zio.StackTracesSpec.spec
            |""".stripMargin
        })
      },
      test("captures the embedded failure") {
        val underlyingFailure =
          for {
            _ <- ZIO.succeed(15)
            f <- ZIO.fail("Oh no!").ensuring(ZIO.dieMessage("other failure"))
          } yield f

        for {
          _     <- ZIO.succeed(25)
          value  = underlyingFailure
          cause <- value.cause
        } yield assert(cause)(causeHasTrace {
          """java.lang.String: Oh no!
            |	at zio.StackTracesSpec.spec.underlyingFailure
            |	at zio.StackTracesSpec.spec
            |	Suppressed: java.lang.RuntimeException: other failure
            |		at zio.StackTracesSpec.spec.underlyingFailure
            |		at zio.StackTracesSpec.spec
            |""".stripMargin
        })
      },
      test("captures a die failure") {
        val underlyingFailure =
          ZIO
            .succeed("ITEM")
            .map(_ => List.empty.head)

        for (cause <- underlyingFailure.cause)
          yield assert(cause)(causeHasTrace {
            if (TestVersion.isScala2)
              """java.util.NoSuchElementException: head of empty list
                |	at scala.collection.immutable.Nil$.head
                |	at zio.StackTracesSpec$.$anonfun$spec
                |	at zio.ZIO.$anonfun$map
                |	at zio.StackTracesSpec.spec.underlyingFailure
                |	at zio.StackTracesSpec.spec
                |""".stripMargin
            else
              """java.util.NoSuchElementException: head of empty list
                |	at scala.collection.immutable.Nil$.head
                |	at zio.StackTracesSpec$.$anonfun
                |	at zio.ZIO.map$$anonfun
                |	at zio.StackTracesSpec.spec.underlyingFailure
                |	at zio.StackTracesSpec.spec
                |""".stripMargin
          })
      } @@ jvmOnly
    ),
    suite("getOrThrowFiberFailure")(
      test("fills in the external stack trace") {
        def call(): Unit = subcall()
        def subcall2()   = ZIO.fail("boom")
        def subcall(): Unit = Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe
            .run(subcall2())
            .getOrThrowFiberFailure()
        }

        assertThrows(call())(exceptionHasTrace {
          if (TestVersion.isScala2)
            """java.lang.String: boom
              |	at zio.StackTracesSpec.spec.subcall2
              |	at zio.StackTracesSpec.spec.subcall
              |	at zio.StackTracesSpec$.$anonfun$spec
              |	at zio.Unsafe$.unsafe
              |	at zio.StackTracesSpec$.subcall
              |	at zio.StackTracesSpec$.call
              |	at zio.StackTracesSpec$.$anonfun$spec
              |	at scala.runtime.java8.JFunction0$mcV$sp.apply
              |	at zio.StackTracesSpec$.assertThrows
              |	at zio.StackTracesSpec$.$anonfun$spec
              |""".stripMargin
          else
            """java.lang.String: boom
              |	at zio.StackTracesSpec.spec.subcall2
              |	at zio.StackTracesSpec.spec.subcall
              |	at zio.StackTracesSpec$.subcall$1$$anonfun
              |	at zio.Unsafe$.unsafe
              |	at zio.StackTracesSpec$.subcall
              |	at zio.StackTracesSpec$.call
              |	at zio.StackTracesSpec$.spec$$anonfun$6$$anonfun
              |	at zio.StackTracesSpec$.spec$$anonfun$6$$anonfun$adapted
              |	at zio.StackTracesSpec$.assertThrows
              |	at zio.StackTracesSpec$.spec$$anonfun
              |	at zio.test.TestConstructor$.apply$$anonfun$1$$anonfun
              |""".stripMargin
        })
      }
    ) @@ jvmOnly,
    suite("getOrThrow")(
      test("fills in the external stack trace (as suppressed)") {
        def call(): Unit = subcall()
        def subcall2()   = ZIO.die(new RuntimeException("boom"))
        def subcall(): Unit = Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe
            .run(subcall2())
            .getOrThrow()
        }

        assertThrows(call())(exceptionHasTrace {
          if (TestVersion.isScala2)
            """java.lang.RuntimeException: boom
              |	at zio.StackTracesSpec$.$anonfun$spec
              |	at zio.ZIO$.$anonfun$die
              |	at zio.ZIO$.$anonfun$failCause
              |	at zio.internal.FiberRuntime.runLoop
              |	at zio.internal.FiberRuntime.evaluateEffect
              |	at zio.internal.FiberRuntime.start
              |	at zio.Runtime$UnsafeAPIV1.runOrFork
              |	at zio.Runtime$UnsafeAPIV1.run
              |	at zio.StackTracesSpec$.$anonfun$spec
              |	at zio.Unsafe$.unsafe
              |	at zio.StackTracesSpec$.subcall
              |	at zio.StackTracesSpec$.call
              |	at zio.StackTracesSpec$.$anonfun$spec
              |	at scala.runtime.java8.JFunction0$mcV$sp.apply
              |	at zio.StackTracesSpec$.assertThrows
              |	at zio.StackTracesSpec$.$anonfun$spec
              |	at zio.internal.FiberRuntime.runLoop
              |	at zio.internal.FiberRuntime.evaluateEffect
              |	at zio.internal.FiberRuntime.evaluateMessageWhileSuspended
              |	at zio.internal.FiberRuntime.drainQueueOnCurrentThread
              |	at zio.internal.FiberRuntime.run
              |	at zio.internal.ZScheduler$$anon$3.run
              |	Suppressed: zio.Cause$FiberTrace: java.lang.RuntimeException: boom
              |	at zio.StackTracesSpec.spec.subcall2
              |	at zio.StackTracesSpec.spec.subcall
              |	at zio.StackTracesSpec$.$anonfun$spec
              |	at zio.Unsafe$.unsafe
              |	at zio.StackTracesSpec$.subcall
              |	at zio.StackTracesSpec$.call
              |	at zio.StackTracesSpec$.$anonfun$spec
              |	at scala.runtime.java8.JFunction0$mcV$sp.apply
              |	at zio.StackTracesSpec$.assertThrows
              |	at zio.StackTracesSpec$.$anonfun$spec
              |""".stripMargin
          else
            """java.lang.RuntimeException: boom
              |	at zio.StackTracesSpec$.subcall2$2$$anonfun
              |	at zio.ZIO$.die$$anonfun
              |	at zio.ZIO$.failCause$$anonfun
              |	at zio.internal.FiberRuntime.runLoop
              |	at zio.internal.FiberRuntime.evaluateEffect
              |	at zio.internal.FiberRuntime.start
              |	at zio.Runtime$UnsafeAPIV1.runOrFork
              |	at zio.Runtime$UnsafeAPIV1.run
              |	at zio.StackTracesSpec$.subcall$2$$anonfun
              |	at zio.Unsafe$.unsafe
              |	at zio.StackTracesSpec$.subcall
              |	at zio.StackTracesSpec$.call
              |	at zio.StackTracesSpec$.spec$$anonfun$7$$anonfun
              |	at zio.StackTracesSpec$.spec$$anonfun$7$$anonfun$adapted
              |	at zio.StackTracesSpec$.assertThrows
              |	at zio.StackTracesSpec$.spec$$anonfun
              |	at zio.test.TestConstructor$.apply$$anonfun$1$$anonfun
              |	at zio.internal.FiberRuntime.runLoop
              |	at zio.internal.FiberRuntime.evaluateEffect
              |	at zio.internal.FiberRuntime.evaluateMessageWhileSuspended
              |	at zio.internal.FiberRuntime.drainQueueOnCurrentThread
              |	at zio.internal.FiberRuntime.run
              |	at zio.internal.ZScheduler$$anon$3.run
              |	Suppressed: zio.Cause$FiberTrace: java.lang.RuntimeException: boom
              |	at zio.StackTracesSpec.spec.subcall2
              |	at zio.StackTracesSpec.spec.subcall
              |	at zio.StackTracesSpec$.subcall$2$$anonfun
              |	at zio.Unsafe$.unsafe
              |	at zio.StackTracesSpec$.subcall
              |	at zio.StackTracesSpec$.call
              |	at zio.StackTracesSpec$.spec$$anonfun$7$$anonfun
              |	at zio.StackTracesSpec$.spec$$anonfun$7$$anonfun$adapted
              |	at zio.StackTracesSpec$.assertThrows
              |	at zio.StackTracesSpec$.spec$$anonfun
              |	at zio.test.TestConstructor$.apply$$anonfun$1$$anonfun
              |""".stripMargin
        })
      }
    ) @@ jvmOnly
  ) @@ sequential

  // set to true to print traces
  private val debug = false

  private val locationRegex =
    """(\$\d+)?\((\w|\$)+\.(scala|java):\d+\)$""".r

  private def assertThrows[T](task: => T)(expected: Assertion[Throwable]) =
    try {
      val value = task
      assert(value)(assertion("expected an exception")(_ => false))
    } catch {
      case exception: Throwable =>
        assert(exception)(expected)
    }

  def strip(trace: String) = {
    if (debug) println(trace)
    val stripped = trace.linesIterator.map(locationRegex.replaceAllIn(_, ""))
    dedupe(stripped.filterNot(_.isEmpty)).mkString("", "\n", "\n")
  }

  def causeHasTrace(expected: String): Assertion[Cause[Any]] =
    hasField("stackTrace", strippedStackTrace, equalTo(expected))

  def exceptionHasTrace(expected: String): Assertion[Throwable] =
    hasField("stackTrace", strippedStackTrace, equalTo(expected))

  def strippedStackTrace(cause: Cause[Any]): String =
    strip(cause.prettyPrint)

  def strippedStackTrace(exception: Throwable): String = {
    val buffer = new ByteArrayOutputStream
    exception.printStackTrace(new PrintStream(buffer))
    strip(buffer.toString)
  }

  def dedupe[A](it: Iterator[A]): Iterator[A] = new AbstractIterator[A] {
    private var current =
      if (it.hasNext) Some(it.next()) else None

    override def hasNext =
      current.isDefined

    override def next(): A = {
      while (it.hasNext) {
        val next = it.next()
        if (!current.contains(next)) {
          try return current.get
          finally current = Some(next)
        }
      }

      try current.get
      finally current = None
    }
  }
}
