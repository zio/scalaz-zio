package zio.concurrent

import zio.stm.TRef
import zio._
import zio.stm.STM

/**
 * An `MVar[A]` is a mutable location that is either empty or contains a value
 * of type `A`. It has two fundamental operations: `put` which fills an `MVar`
 * if it is empty and blocks otherwise, and `take` which empties an `MVar` if it
 * is full and blocks otherwise. They can be used in multiple different ways:
 *
 *   - As synchronized mutable variables,
 *   - As channels, with `take` and `put` as `receive` and `send`, and
 *   - As a binary semaphore `MVar[Unit]`, with `take` and `put` as `wait` and
 *     `signal`.
 *
 * They were introduced in the paper "Concurrent Haskell" by Simon Peyton Jones,
 * Andrew Gordon and Sigbjorn Finne.
 */
final class MVar[A] private (private val content: TRef[Option[A]]) {

  /**
   * Check whether the `MVar` is empty.
   *
   * Notice that the boolean value returned is just a snapshot of the state of
   * the `MVar`. By the time you get to react on its result, the `MVar` may have
   * been filled (or emptied) - so be extremely careful when using this
   * operation. Use `tryTake` instead if possible.
   */
  def isEmpty: UIO[Boolean] =
    content.get.map(_.isEmpty).commit

  /**
   * A slight variation on `update` that allows a value to be returned (`b`) in
   * addition to the modified value of the `MVar`.
   */
  def modify[B](f: A => (B, A)): UIO[B] =
    content.get.collect { case Some(a) =>
      val (b, newA) = f(a)
      (b, Some(newA))
    }.flatMap { case (b, newA) => content.set(newA) as b }.commit

  /**
   * Put a value into an `MVar`. If the `MVar` is currently full, `put` will
   * wait until it becomes empty.
   */
  def put(x: A): UIO[Unit] =
    (content.get.collect { case None => () } *> content.set(Some(x))).commit

  /**
   * Atomically read the contents of an `MVar`. If the `MVar` is currently
   * empty, `read` will wait until it is full. `read` is guaranteed to receive
   * the next `put`.
   */
  def read: UIO[A] =
    content.get.collect { case Some(x) => x }.commit

  /**
   * Take a value from an `MVar`, put a new value into the `MVar` and return the
   * value taken.
   */
  def swap(x: A): UIO[A] =
    (for {
      ref <- content.get
      y <- ref match {
             case Some(y) => content.set(Some(x)) as y
             case None    => STM.retry
           }
    } yield y).commit

  /**
   * Return the contents of the `MVar`. If the `MVar` is currently empty, `take`
   * will wait until it is full. After a `take`, the `MVar` is left empty.
   */
  def take: UIO[A] =
    (content.get.collect { case Some(a) => a }.flatMap(a => content.set(None) as a)).commit

  /**
   * A non-blocking version of `put`. The `tryPut` function attempts to put the
   * value `x` into the `MVar`, returning `true` if it was successful, or
   * `false` otherwise.
   */
  def tryPut(x: A): UIO[Boolean] =
    (content.get.flatMap {
      case _: Some[_] => STM.succeed(false)
      case None       => content.set(Some(x)) as true
    }).commit

  /**
   * A non-blocking version of `read`. The `tryRead` function returns
   * immediately, with `None` if the `MVar` was empty, or `Some(x)` if the
   * `MVar` was full with contents `x`.
   */
  def tryRead: UIO[Option[A]] =
    content.get.commit

  /**
   * A non-blocking version of `take`. The `tryTake` action returns immediately,
   * with `None` if the `MVar` was empty, or `Some(x)` if the `MVar` was full
   * with contents `x`. After `tryTake`, the `MVar` is left empty.
   */
  def tryTake: UIO[Option[A]] =
    (for {
      c <- content.get
      a <- c match {
             case Some(a) => content.set(None) *> STM.succeed(Some(a))
             case None    => STM.succeed(None)
           }
    } yield a).commit

  /**
   * Replaces the contents of an `MVar` with the result of `f(a)`.
   */
  def update(f: A => A): UIO[Unit] =
    content.get.collect { case Some(a) => Some(f(a)) }.flatMap(content.set).commit
}

object MVar {

  /** Creates an `MVar` which is initially empty */
  def empty[A]: UIO[MVar[A]] =
    TRef.make(Option.empty[A]).map(new MVar(_)).commit

  /** Create an MVar which contains the supplied value */
  def make[A](a: A): UIO[MVar[A]] =
    TRef.make(Option(a)).map(new MVar(_)).commit
}
