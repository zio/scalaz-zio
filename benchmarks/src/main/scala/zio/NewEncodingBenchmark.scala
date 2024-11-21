package zio

import org.openjdk.jmh.annotations.{Scope => JScope, _}

import java.util.concurrent.TimeUnit

@State(JScope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class NewEncodingBenchmark {

  @Param(Array("5", "10")) //, "20", "40"))
  var depth: Int = _

  @Benchmark
  def classicBroadFlatMap(): Int = {
    def fib(n: Int): Classic[Int] =
      if (n <= 1) Classic.succeed[Int](n)
      else
        fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => Classic.succeed(a + b)))

    fib(depth).unsafeRun()
  }

  @Benchmark
  def experimentBroadFlatMap(): Int = {
    import partial._

    def fib(n: Int): Task[Int] =
      if (n <= 1) Task.succeed(n)
      else
        fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => Task.succeed(a + b)))

    fib(depth).unsafeRun()
  }

  @Benchmark
  def experimentFullBroadFlatMap(): Int = {
    import capture._

    def fib(n: Int): Task[Int] =
      if (n <= 1) Task.succeed(n)
      else
        fib(n - 1).flatMap(a => fib(n - 2).flatMap(b => Task.succeed(a + b)))

    fib(depth).unsafeRun()
  }
}

sealed trait Classic[+A] { self =>
  def unsafeRun(): A = Classic.eval(self)

  def flatMap[B](f: A => Classic[B]): Classic[B] = Classic.FlatMap(self, f)

  def map[B](f: A => B): Classic[B] = self.flatMap(a => Classic.succeed(f(a)))
}
object Classic {
  case class Succeed[A](thunk: () => A)                                 extends Classic[A]
  case class FlatMap[A, B](first: Classic[A], andThen: A => Classic[B]) extends Classic[B]

  def succeed[A](a: => A): Classic[A] = Succeed(() => a)

  private def eval[A](effect: Classic[A]): A = {
    type Erased  = Classic[Any]
    type ErasedK = Any => Classic[Any]

    val stack = zio.internal.Stack[ErasedK]()

    var cur: Erased = effect
    var done: A     = null.asInstanceOf[A]

    while (done == null) {
      cur match {
        case FlatMap(first, andThen) =>
          cur = first
          stack.push(andThen.asInstanceOf[ErasedK])

        case Succeed(thunk) =>
          val value = thunk()

          if (stack.isEmpty) {
            cur = null
            done = value.asInstanceOf[A]
          } else {
            cur = stack.pop()(value)
          }
      }
    }

    done
  }
}

package partial {
  sealed trait Task[+A] { self =>
    def unsafeRun(): A = Task.eval(self)

    def flatMap[B](f: A => Task[B]): Task[B] = Task.FlatMap(self, f)

    def map[B](f: A => B): Task[B] = self.flatMap(a => Task.succeed(f(a)))
  }
  object Task {
    case class Succeed[A](thunk: () => A)                           extends Task[A]
    case class FlatMap[A, B](first: Task[A], andThen: A => Task[B]) extends Task[B]

    def succeed[A](a: => A): Task[A] = Succeed(() => a)

    private def eval[A](effect: Task[A]): A = {
      type Erased  = Task[Any]
      type ErasedK = Any => Task[Any]

      var cur: Erased = effect
      var done: A     = null.asInstanceOf[A]

      while (done == null) {
        cur match {
          case FlatMap(first, andThen) =>
            cur = andThen.asInstanceOf[ErasedK](eval(first))

          case Succeed(thunk) =>
            val value = thunk()

            done = value.asInstanceOf[A]
        }
      }

      done
    }
  }
}

package capture {

  import scala.util.control.NoStackTrace
  import scala.annotation.tailrec
  sealed trait Task[+A] { self =>
    def unsafeRun(): A = Task.eval(self)

    def catchAll[A1 >: A](t: Throwable => Task[A1]): Task[A1] =
      Task.Rescue(self, t)

    def flatMap[B](f: A => Task[B]): Task[B] = Task.FlatMap(self, f)

    def map[B](f: A => B): Task[B] = self.flatMap(a => Task.succeed(f(a)))
  }
  object Task {
    sealed trait Continuation[-A, +B] { self =>
      def onSuccess(a: A): Task[B]

      def erase: Continuation[Any, Any] = self.asInstanceOf[Continuation[Any, Any]]
    }
    sealed trait FailureCont[-A, +B] extends Continuation[A, B] { self =>
      def onFailure(t: Throwable): Task[B]

      override def erase: FailureCont[Any, Any] = self.asInstanceOf[FailureCont[Any, Any]]
    }

    case class Succeed[A](thunk: () => A) extends Task[A]
    case class FlatMap[A, B](first: Task[A], andThen: A => Task[B]) extends Task[B] with Continuation[A, B] {
      def onSuccess(a: A): Task[B] = andThen(a)
    }
    case class Rescue[A](first: Task[A], rescuer: Throwable => Task[A]) extends Task[A] with FailureCont[A, A] {
      def onSuccess(a: A): Task[A] = Task.succeed(a)

      def onFailure(t: Throwable): Task[A] = rescuer(t)
    }
    case class Async[A](registerCallback: (Task[A] => Unit) => Unit) extends Task[A]

    type Erased  = Task[Any]
    type ErasedK = Continuation[Any, Any]

    sealed abstract class ReifyStack extends Exception with NoStackTrace {
      def stack: ChunkBuilder[ErasedK]
    }

    final case class AsyncJump(registerCallback: (Task[Any] => Unit) => Unit, stack: ChunkBuilder[ErasedK])
        extends ReifyStack

    final case class Trampoline(effect: Task[Any], stack: ChunkBuilder[ErasedK]) extends ReifyStack

    final case class ErrorWrapper(error: Throwable, stack: ChunkBuilder[ErasedK]) extends ReifyStack

    def succeed[A](a: => A): Task[A] = Succeed(() => a)

    def fail(t: => Throwable): Task[Nothing] = succeed(throw ErrorWrapper(t, ChunkBuilder.make()))

    def eval[A](effect: Task[A]): A = {
      def loop[A](effect: Task[A], depth: Int, stack: Chunk[ErasedK]): A = {
        var cur: Erased = effect
        var done: A     = null.asInstanceOf[A]
        var stackIndex  = 0

        if (depth > 1000) {
          throw Trampoline(effect, ChunkBuilder.make())
        } else {
          try {
            while (done == null) {
              try {
                cur match {
                  case flatMap @ FlatMap(_, _) =>
                    try {
                      cur = flatMap.erase.onSuccess(loop(flatMap.first, depth + 1, null))
                    } catch {
                      case reifyStack: ReifyStack =>
                        reifyStack.stack += flatMap.erase

                        throw reifyStack
                    }

                  case Async(registerCallback) => throw AsyncJump(registerCallback, ChunkBuilder.make())

                  case Succeed(thunk) =>
                    val value = thunk()

                    if ((stack ne null) && stackIndex < stack.length) {
                      cur = stack(stackIndex).onSuccess(value)

                      stackIndex += 1
                    } else {
                      done = value.asInstanceOf[A]
                    }

                  case rescue @ Rescue(first, rescuer) =>
                    try {
                      val value = loop(first, depth + 1, null)

                      if ((stack ne null) && stackIndex < stack.length) {
                        cur = stack(stackIndex).onSuccess(value)

                        stackIndex += 1
                      } else {
                        done = value.asInstanceOf[A]
                      }
                    } catch {
                      case ErrorWrapper(throwable, stack) =>
                        val _ = stack // TODO: Use stack to attach trace to throwable

                        cur = rescuer(throwable)

                      case reifyStack: ReifyStack =>
                        reifyStack.stack += rescue.erase

                        throw reifyStack
                    }
                }
              } catch {
                case error @ ErrorWrapper(throwable, _) =>
                  // TODO: attach trace to throwable
                  cur = null

                  if (stack ne null) {
                    while (stackIndex < stack.length) {
                      stack(stackIndex) match {
                        case failure: FailureCont[_, _] =>
                          cur = failure.onFailure(throwable)

                        case _: FlatMap[_, _] => ()
                      }
                      stackIndex += 1
                    }
                  }

                  if (cur eq null) throw error
              }
            }
          } catch {
            case reifyStack: ReifyStack =>
              if ((stack ne null) && stackIndex <= stack.length) {
                reifyStack.stack ++= stack.drop(stackIndex)
              }

              throw reifyStack
          }
        }

        done
      }

      def resumeOuterLoop(effect: Task[Any], stack: Chunk[ErasedK]): Unit = {
        outerLoop(effect, stack)
        ()
      }

      @tailrec
      def outerLoop(effect: Task[Any], stack: Chunk[ErasedK]): Any =
        try {
          loop(effect, 0, stack)
        } catch {
          case t: Trampoline => outerLoop(effect, t.stack.result())

          case a: AsyncJump =>
            a.registerCallback { value =>
              resumeOuterLoop(value, a.stack.result())
            }

            null
        }

      outerLoop(effect, null).asInstanceOf[A]
    }
  }
}

package stack_versus_heap_10 {
  sealed trait Task[+A] { self =>
    def unsafeRun(): A = Task.eval(self)

    def flatMap[B](f: A => Task[B]): Task[B] = Task.FlatMap(self, f)

    def map[B](f: A => B): Task[B] = self.flatMap(a => Task.succeed(f(a)))
  }
  object Task {
    case class Succeed[A](thunk: () => A)                           extends Task[A]
    case class FlatMap[A, B](first: Task[A], andThen: A => Task[B]) extends Task[B]

    def succeed[A](a: => A): Task[A] = Succeed(() => a)

    private def eval[A](effect: Task[A]): A = {
      type Erased  = Task[Any]
      type ErasedK = Any => Task[Any]

      var cur: Erased                             = effect
      var done: A                                 = null.asInstanceOf[A]
      var k1, k2, k3, k4, k5, k6, k7, k8, k9, k10 = null.asInstanceOf[ErasedK]

      while (done == null) {
        cur match {
          case FlatMap(first, andThen) =>
            if (k10 ne null) {
              cur = andThen(eval(first))
            } else {
              k10 = k9; k9 = k8; k8 = k7; k7 = k6; k6 = k5;
              k5 = k4; k4 = k3; k3 = k2; k2 = k1; k1 = andThen.asInstanceOf[ErasedK]

              cur = first
            }

          case Succeed(thunk) =>
            val value = thunk()

            if (k1 ne null) {
              cur = k1(value)

              k1 = k2; k2 = k3; k3 = k4; k4 = k5; k5 = k6;
              k6 = k7; k7 = k8; k8 = k9; k9 = k10; k10 = null.asInstanceOf[ErasedK]
            } else {
              cur = null
              done = value.asInstanceOf[A]
            }
        }
      }

      done
    }
  }
}
