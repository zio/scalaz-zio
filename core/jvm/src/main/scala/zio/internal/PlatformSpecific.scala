package zio.internal

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.{Collections, Map => JMap, Set => JSet, WeakHashMap}

private[zio] trait PlatformSpecific {

  /**
   * Adds a shutdown hook that executes the specified action on shutdown.
   */
  def addShutdownHook(action: () => Unit): Unit =
    java.lang.Runtime.getRuntime.addShutdownHook {
      new Thread {
        override def run() = action()
      }
    }

  /**
   * Returns the name of the thread group to which this thread belongs. This
   * is a side-effecting method.
   */
  final def getCurrentThreadGroup: String =
    Thread.currentThread.getThreadGroup.getName

  /**
   * Returns whether the current platform is ScalaJS.
   */
  val isJS = false

  /**
   * Returns whether the currently platform is the JVM.
   */
  val isJVM = true

  /**
   * Returns whether the currently platform is Scala Native.
   */
  val isNative = false

  final def newWeakHashMap[A, B](): JMap[A, B] =
    Collections.synchronizedMap(new WeakHashMap[A, B]())

  final def newConcurrentWeakSet[A](): JSet[A] =
    Collections.synchronizedSet(newWeakSet[A]())

  final def newWeakSet[A](): JSet[A] =
    Collections.newSetFromMap(new WeakHashMap[A, java.lang.Boolean]())

  final def newConcurrentSet[A](): JSet[A] = ConcurrentHashMap.newKeySet[A]()

  final def newWeakReference[A](value: A): () => A = {
    val ref = new WeakReference[A](value)

    () => ref.get()
  }
}
