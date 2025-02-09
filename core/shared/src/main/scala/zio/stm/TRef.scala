/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.stm

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stm.ZSTM.internal._

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

/**
 * A `TRef` is a purely functional description of a mutable reference that can
 * be modified as part of a transactional effect. The fundamental operations of
 * a `TRef` are `set` and `get`. `set` transactionally sets the reference to a
 * new value. `get` gets the current value of the reference.
 *
 * NOTE: While `TRef` provides the transactional equivalent of a mutable
 * reference, the value inside the `TRef` should be immutable. For performance
 * reasons `TRef` is implemented in terms of compare and swap operations rather
 * than synchronization. These operations are not safe for mutable values that
 * do not support concurrent access.
 */
final class TRef[A] private (
  private[stm] val versioned: AtomicReference[A],
  @volatile private[stm] var todo: Map[TxnId, Todo] = Map.empty
) extends Serializable { self =>

  @deprecated("kept for binary compatibility only", "2.1.8")
  def this(versioned: Versioned[A], todo: AtomicReference[Map[TxnId, Todo]]) =
    this(new AtomicReference(versioned.value), todo.get())

  private[stm] val lock: ZSTMLockSupport.Lock = ZSTMLockSupport.Lock(true)

  private val id = TRef.idCounter.incrementAndGet()

  private[this] val hc = super.hashCode()

  override def hashCode(): Int = hc

  /**
   * Retrieves the value of the `TRef`.
   */
  def get: USTM[A] =
    ZSTM.Effect((journal, _, _) => getOrMakeEntry(journal).unsafeGet[A])

  /**
   * Sets the value of the `TRef` and returns the old value.
   */
  def getAndSet(a: A): USTM[A] =
    ZSTM.Effect { (journal, _, _) =>
      val entry    = getOrMakeEntry(journal)
      val oldValue = entry.unsafeGet[A]
      entry.unsafeSet(a)
      oldValue
    }

  /**
   * Updates the value of the variable and returns the old value.
   */
  def getAndUpdate(f: A => A): USTM[A] =
    ZSTM.Effect { (journal, _, _) =>
      val entry    = getOrMakeEntry(journal)
      val oldValue = entry.unsafeGet[A]
      entry.unsafeSet(f(oldValue))
      oldValue
    }

  /**
   * Updates some values of the variable but leaves others alone, returning the
   * old value.
   */
  def getAndUpdateSome(f: PartialFunction[A, A]): USTM[A] =
    getAndUpdate(f orElse { case a => a })

  /**
   * Updates the value of the variable, returning a function of the specified
   * value.
   */
  def modify[B](f: A => (B, A)): USTM[B] =
    ZSTM.Effect { (journal, _, _) =>
      val entry                = getOrMakeEntry(journal)
      val (retValue, newValue) = f(entry.unsafeGet[A])
      entry.unsafeSet(newValue)
      retValue
    }

  /**
   * Updates the value of the variable, returning a function of the specified
   * value.
   */
  def modifySome[B](default: B)(f: PartialFunction[A, (B, A)]): USTM[B] =
    modify(a => f.lift(a).getOrElse((default, a)))

  /**
   * Sets the value of the `TRef`.
   */
  def set(a: A): USTM[Unit] =
    ZSTM.Effect { (journal, _, _) =>
      val entry = getOrMakeEntry(journal)
      entry.unsafeSet(a)
      ()
    }

  override def toString: String =
    s"TRef(id = ${self.hashCode()}, versioned.value = ${versioned})"

  /**
   * Updates the value of the variable.
   */
  def update(f: A => A): USTM[Unit] =
    ZSTM.Effect { (journal, _, _) =>
      val entry = journal.getOrElseUpdate(self, newEntry())
      entry.unsafeUpdate(f.asInstanceOf[Any => Any])
      ()
    }

  /**
   * Updates the value of the variable and returns the new value.
   */
  def updateAndGet(f: A => A): USTM[A] =
    ZSTM.Effect { (journal, _, _) =>
      val entry    = getOrMakeEntry(journal)
      val newValue = f(entry.unsafeGet[A])
      entry.unsafeSet(newValue)
      newValue
    }

  /**
   * Updates some values of the variable but leaves others alone.
   */
  def updateSome(f: PartialFunction[A, A]): USTM[Unit] =
    update(f orElse { case a => a })

  /**
   * Updates some values of the variable but leaves others alone, returning the
   * new value.
   */
  def updateSomeAndGet(f: PartialFunction[A, A]): USTM[A] =
    updateAndGet(f orElse { case a => a })

  private[stm] def getOrMakeEntry(journal: Journal): Entry =
    journal.getOrElseUpdate(self, newEntry())

  private[stm] def unsafeGet(journal: Journal): A =
    getOrMakeEntry(journal).unsafeGet

  private[stm] def unsafeSet(journal: Journal, a: A): Unit =
    getOrMakeEntry(journal).unsafeSet(a)

  private[this] val newEntry = () => Entry(self.asInstanceOf[TRef[A & AnyRef]])
}

object TRef {
  private val idCounter: AtomicLong = new AtomicLong(0L)

  implicit val ordering: Ordering[TRef[?]] = new Ordering[TRef[?]] {
    def compare(x: TRef[?], y: TRef[?]): Int = java.lang.Long.compare(x.id, y.id)
  }

  /**
   * Makes a new `TRef` that is initialized to the specified value.
   */
  def make[A](a: => A): USTM[TRef[A]] =
    ZSTM.succeed(unsafe.make(a)(Unsafe.unsafe))

  /**
   * A convenience method that makes a `TRef` and immediately commits the
   * transaction to extract the value out.
   */
  def makeCommit[A](a: => A)(implicit trace: Trace): UIO[TRef[A]] =
    STM.atomically(make(a))

  object unsafe {
    def make[A](a: A)(implicit unsafe: Unsafe): TRef[A] =
      TRef.unsafeMake(a)
  }

  private[stm] def unsafeMake[A](a: A): TRef[A] =
    new TRef(new AtomicReference[A](a))
}
