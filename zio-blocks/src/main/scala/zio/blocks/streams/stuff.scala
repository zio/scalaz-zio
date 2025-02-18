package zio.blocks.streams

import scala.util.control.NoStackTrace

trait ZSource[-R, +E, +A] {
  def unsafeStart(r: R): ZSourceFiber[E, A]

  def merge[R1 <: R, E1 >: E, A1 >: A](that: => ZSource[R1, E1, A1]): ZSource[R1, E1, A1] = ???

  def mapVector[B](f: A => Vector[B]): ZSource[R, E, B] = ???

  // flatMap[B](f: A => ZSource[R, E, B]): ZSource[R, E, B]

  def buffer(n: Int): ZSource[R, E, A]
}

abstract class ZSourceFiber[+E, +A] { self =>

  final def map[B](f: A => B): ZSourceFiber[E, B] = ZSourceFiber.Map(self, f)

  final def flatMap[E1 >: E, B](f: A => ZSourceFiber[E1, B]): ZSourceFiber[E1, B] = ZSourceFiber.FlatMap(self, f)

  def unsafePull(): A

  def unsafeAsyncPull(runnable: Runnable): Boolean

  def unsafePullByte(implicit ev: A <:< Byte): Int = {
    val a = unsafePull()

    if (a != null) ev(a).toInt else -1
  }

  def unsafeClose(): Boolean

  def unsafeAsyncClose(runnable: Runnable): Boolean
}
object ZSourceFiber {
  case object Empty extends Exception with NoStackTrace

  final case class Map[E, A, B](self: ZSourceFiber[E, A], f: A => B) extends ZSourceFiber[E, B] {
    def unsafePull(): B = {
      val a = self.unsafePull()

      if (a != null) f(a) else null.asInstanceOf[B]
    }

    def unsafeAsyncPull(runnable: Runnable): Boolean = self.unsafeAsyncPull(runnable)

    def unsafeClose(): Boolean = self.unsafeClose()

    def unsafeAsyncClose(runnable: Runnable): Boolean = self.unsafeAsyncClose(runnable)
  }
  final case class FlatMap[E, A, B](self: ZSourceFiber[E, A], f: A => ZSourceFiber[E, B]) extends ZSourceFiber[E, B] {
    private var current: ZSourceFiber[E, B] = _

    private def attemptCurrent(): Boolean = {
      if (current eq null) {
        try {
          val a = self.unsafePull()

          if (a != null) {
            current = f(a)
          }
        } catch {
          case Empty => ()
        }
      }

      current ne null
    }

    def unsafePull(): B =
      if (attemptCurrent()) {
        val b = current.unsafePull()

        if (b != null) b
        else {
          current = null
          unsafePull()
        }
      } else null.asInstanceOf[B]

    def unsafeAsyncPull(runnable: Runnable): Boolean =
      if (attemptCurrent()) {
        current.unsafeAsyncPull(runnable)
      } else self.unsafeAsyncPull(runnable)

    def unsafeClose(): Boolean =
      if (current ne null) current.unsafeClose() && self.unsafeClose()
      else self.unsafeClose()

    def unsafeAsyncClose(runnable: Runnable): Boolean = {
      if (current ne null) current.unsafeAsyncClose(runnable)

      self.unsafeAsyncClose(runnable)
    }
  }
}

trait ZSink[-R, +E, -A, +B] {
  def unsafeStart[R1 <: R, E1 >: E, A1 <: A](r: R, fiber: ZSourceFiber[E1, A1]): ZSinkFiber[E1, A1, B]

  def concurrently(n: Int): ZSink[R, E, A, B]
}

trait ZSinkFiber[+E, -A, +B] {
  def unsafeDone(): B

  def unsafeAsyncDone(runnable: Runnable): Boolean
}
