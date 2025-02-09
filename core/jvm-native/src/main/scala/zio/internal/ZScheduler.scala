/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

package zio.internal

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.{ConcurrentLinkedQueue, ThreadLocalRandom}
import scala.collection.mutable

/**
 * A `ZScheduler` is an `Executor` that is optimized for running ZIO
 * applications. Inspired by "Making the Tokio Scheduler 10X Faster" by Carl
 * Lerche. [[https://tokio.rs/blog/2019-10-scheduler]]
 */
private final class ZScheduler(autoBlocking: Boolean) extends Executor {

  import Trace.{empty => emptyTrace}
  import ZScheduler.{poolSize, workerOrNull}

  private[this] val globalQueue     = new PartitionedLinkedQueue[Runnable](poolSize * 4)
  private[this] val cache           = new ConcurrentLinkedQueue[ZScheduler.Worker]()
  private[this] val idle            = new ConcurrentLinkedQueue[ZScheduler.Worker]()
  private[this] val globalLocations = makeLocations()
  private[this] val state           = new AtomicInteger(poolSize << 16)
  private[this] val workers         = Array.ofDim[ZScheduler.Worker](poolSize)

  @volatile private[this] var blockingLocations: Set[Trace] = Set.empty

  (0 until poolSize).foreach { workerId =>
    val worker = makeWorker()
    worker.setName(workerId)
    worker.setDaemon(true)
    workers(workerId) = worker
  }
  workers.foreach(_.start())

  if (autoBlocking) {
    val supervisor = makeSupervisor()
    supervisor.setName("ZScheduler-Supervisor")
    supervisor.setDaemon(true)
    supervisor.start()
  }

  def metrics(implicit unsafe: Unsafe): Option[ExecutionMetrics] = {
    val metrics = new ExecutionMetrics {
      def capacity: Int =
        Int.MaxValue
      def concurrency: Int =
        poolSize
      def dequeuedCount: Long = {
        var dequeued = 0L
        var i        = 0
        while (i != poolSize) {
          val worker = workers(i)
          dequeued += worker.opCount
          i += 1
        }
        dequeued
      }
      def enqueuedCount: Long = {
        var enqueued = 0L
        var i        = 0
        while (i != poolSize) {
          val worker = workers(i)
          enqueued += worker.opCount
          enqueued += worker.localQueue.size()
          if (worker.nextRunnable ne null) enqueued += 1
          i += 1
        }
        enqueued += globalQueue.size()
        enqueued
      }
      def size: Int = {
        var i    = 0
        var size = 0
        while (i != poolSize) {
          val worker = workers(i)
          size += worker.localQueue.size()
          if (worker.nextRunnable ne null) size += 1
          i += 1
        }
        size += globalQueue.size()
        size
      }
      def workersCount: Int = {
        val currentState = state.get
        (currentState & 0xffff0000) >> 16
      }
    }
    Some(metrics)
  }

  override def stealWork(depth: Int): Boolean = {
    val worker = workerOrNull()
    if (worker ne null) {
      var runnable = null.asInstanceOf[Runnable]
      if (worker.nextRunnable ne null) {
        runnable = worker.nextRunnable
        worker.nextRunnable = null
      } else {
        runnable = worker.localQueue.poll(null)
        if (runnable eq null) {
          runnable = globalQueue.poll()
        }
      }

      if (runnable ne null) {
        if (runnable.isInstanceOf[FiberRunnable]) {
          val fiberRunnable = runnable.asInstanceOf[FiberRunnable]
          worker.currentRunnable = fiberRunnable
          fiberRunnable.run(depth)
        } else {
          runnable.run()
        }
        true
      } else {
        worker.nextRunnable = runnable
        false
      }
    } else {
      false
    }
  }

  def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean = {
    val worker = workerOrNull()
    if (isBlocking(worker, runnable)) {
      submitBlocking(runnable)
    } else {
      if ((worker eq null) || worker.blocking) {
        globalQueue.offer(runnable)
      } else if (!worker.localQueue.offer(runnable)) {
        handleFullWorkerQueue(worker, runnable)
      } else ()
      val currentState = state.get
      maybeUnparkWorker(currentState)
      true
    }
  }

  override def submitAndYield(runnable: Runnable)(implicit unsafe: Unsafe): Boolean = {
    val worker = workerOrNull()
    if (isBlocking(worker, runnable)) {
      submitBlocking(runnable)
    } else {
      var notify = false
      if ((worker eq null) || worker.blocking) {
        globalQueue.offer(runnable)
        notify = true
      } else if ((worker.nextRunnable eq null) && worker.localQueue.isEmpty()) {
        worker.nextRunnable = runnable
      } else if (worker.localQueue.offer(runnable)) {
        notify = true
      } else {
        handleFullWorkerQueue(worker, runnable)
        notify = true
      }
      if (notify) {
        val currentState = state.get
        maybeUnparkWorker(currentState)
      }
      true
    }
  }

  private def handleFullWorkerQueue(worker: ZScheduler.Worker, runnable: Runnable): Unit = {
    val rnd    = ThreadLocalRandom.current
    val polled = worker.localQueue.pollUpTo(128)
    globalQueue.offerAll(polled, rnd)
    val accepted = worker.localQueue.offer(runnable)
    if (!accepted) {
      // We should never ever need to come here, this is just a precaution in the case we've introduced a bug
      globalQueue.offer(runnable, rnd)
    }
  }

  private[this] def isBlocking(worker: ZScheduler.Worker, runnable: Runnable): Boolean =
    if (autoBlocking && runnable.isInstanceOf[FiberRunnable]) {
      val fiberRunnable = runnable.asInstanceOf[FiberRunnable]
      val location      = fiberRunnable.location
      if ((location ne null) && (location ne emptyTrace)) {
        if (worker eq null) globalLocations.put(location)
        else worker.submittedLocations.put(location)
        blockingLocations.contains(location)
      } else false
    } else false

  private[this] def makeLocations(): ZScheduler.Locations =
    if (autoBlocking) new ZScheduler.Locations.Enabled
    else ZScheduler.Locations.Disabled

  private[this] def makeSupervisor(): ZScheduler.Supervisor =
    new ZScheduler.Supervisor {

      private def countSubmittedAt(location: Trace): Long = {
        var count = globalLocations.get(location)
        var i     = 0
        while (i < poolSize) {
          val workerCount = workers(i).submittedLocations.get(location)
          count += workerCount
          i += 1
        }
        count
      }

      override def run(): Unit = {
        val identifiedLocations = makeLocations()
        val previousOpCounts    = Array.fill(poolSize)(-1L)
        while (!isInterrupted) {
          var workerId = 0
          while (workerId < poolSize) {
            val currentWorker = workers(workerId)
            if (currentWorker.active) {
              val currentOpCount  = currentWorker.opCount
              val previousOpCount = previousOpCounts(workerId)
              if (currentOpCount == previousOpCount) {
                val currentRunnable = currentWorker.currentRunnable
                if (currentRunnable.isInstanceOf[FiberRunnable]) {
                  val fiberRunnable = currentRunnable.asInstanceOf[FiberRunnable]
                  val location      = fiberRunnable.location
                  if (location ne emptyTrace) {
                    val identifiedCount = identifiedLocations.put(location)
                    val submittedCount  = countSubmittedAt(location)
                    if (submittedCount > 64 && identifiedCount >= submittedCount / 2) {
                      blockingLocations += location
                    }
                  }
                }
                previousOpCounts(workerId) = -1L
                currentWorker.markAsBlocking()
              } else {
                previousOpCounts(workerId) = currentOpCount
              }
            } else {
              previousOpCounts(workerId) = -1L
            }
            workerId += 1
          }
          val deadline = java.lang.System.currentTimeMillis() + 100
          var loop     = true
          while (loop) {
            LockSupport.parkUntil(deadline)
            loop = java.lang.System.currentTimeMillis() < deadline
          }
        }
      }
    }

  private[this] def makeWorker(): ZScheduler.Worker =
    new ZScheduler.Worker {
      self =>
      override val submittedLocations = makeLocations()

      override def run(): Unit = {
        var currentBlocking = false
        var currentOpCount  = 0L
        val random          = ThreadLocalRandom.current
        var runnable        = null.asInstanceOf[Runnable]
        var searching       = false
        while (!isInterrupted) {
          currentBlocking = blocking
          val currentNextRunnable = nextRunnable
          if (currentBlocking) ()
          else if (currentNextRunnable ne null) {
            runnable = currentNextRunnable
            nextRunnable = null
          } else {
            if ((currentOpCount & 63) == 0) {
              runnable = globalQueue.poll(random)
              if (runnable eq null) {
                runnable = localQueue.poll(null)
              }
            } else {
              runnable = localQueue.poll(null)
              if (runnable eq null) {
                runnable = globalQueue.poll(random)
              }
            }
            if (runnable eq null) {
              if (!searching) {
                val currentState  = state.get
                val currentActive = currentState & 0xffff
                if (2 * currentActive < poolSize) {
                  state.getAndIncrement()
                  searching = true
                }
              }
              if (searching) {
                var i      = 0
                var loop   = true
                val offset = random.nextInt(poolSize)
                while (i != poolSize && loop) {
                  val index  = (i + offset) % poolSize
                  val worker = workers(index)
                  if ((worker ne self) && !worker.blocking) {
                    val size = worker.localQueue.size()
                    if (size > 0) {
                      val runnables  = worker.localQueue.pollUpTo(size - size / 2)
                      val nRunnables = runnables.size
                      if (nRunnables > 0) {
                        val iter = runnables.iterator
                        runnable = iter.next()
                        if (nRunnables > 1) localQueue.offerAll(iter, nRunnables - 1)
                        currentBlocking = blocking
                        if (currentBlocking) {
                          val runnables = localQueue.pollUpTo(256)
                          if (runnables.nonEmpty) {
                            globalQueue.offerAll(runnables, random)
                          }
                        }
                        loop = false
                      }
                    }
                  }
                  i += 1
                }
                if (runnable eq null) {
                  runnable = globalQueue.poll(random)
                }
              }
            }
          }
          if (runnable eq null) {
            val currentState =
              if (currentBlocking && searching) state.decrementAndGet()
              else if (currentBlocking) state.get
              else if (searching) state.addAndGet(0xfffeffff)
              else state.addAndGet(0xffff0000)
            val currentSearching = currentState & 0xffff
            active = false
            if (currentBlocking) {
              cache.offer(self)
            } else {
              idle.offer(self)
            }
            if (currentSearching == 0 && searching) {
              var i      = 0
              var notify = false
              while (i != poolSize && !notify) {
                val worker = workers(i)
                notify = !worker.localQueue.isEmpty()
                i += 1
              }
              if (!notify) {
                notify = !globalQueue.isEmpty()
              }
              if (notify) {
                val currentState = state.get
                maybeUnparkWorker(currentState)
              }
            }
            while (!active && !isInterrupted) {
              LockSupport.park()
            }
            searching = true
          } else {
            if (searching) {
              searching = false
              val currentState = state.decrementAndGet()
              maybeUnparkWorker(currentState)
            }
            currentRunnable = runnable
            runnable.run()
            runnable = null
            currentRunnable = runnable
            currentOpCount += 1
            opCount = currentOpCount
          }
        }
      }

      // NOTE: Synchronized block in case the supervisor attempts to mark the worker as blocking at the same time
      // as an external call
      def markAsBlocking(): Unit = synchronized {
        if (blocking) ()
        else {
          blocking = true
          val idx = workers.indexOf(self)
          if (idx >= 0) {
            val runnables = self.localQueue.pollUpTo(256)
            if (nextRunnable ne null) {
              globalQueue.offer(nextRunnable)
              nextRunnable = null
            }
            globalQueue.offerAll(runnables)
            val worker = cache.poll()
            if (worker eq null) {
              val worker = makeWorker()
              worker.setName(idx)
              worker.setDaemon(true)
              workers(idx) = worker
              worker.start()
            } else {
              state.getAndIncrement()
              worker.setName(idx)
              workers(idx) = worker
              worker.blocking = false
              worker.active = true
              LockSupport.unpark(worker)
            }
          }
        }
      }
    }

  private def maybeUnparkWorker(currentState: Int): Unit = {
    val currentSearching = currentState & 0xffff
    val currentActive    = (currentState & 0xffff0000) >> 16
    if (currentActive != poolSize && currentSearching == 0) {
      val worker = idle.poll()
      if (worker ne null) {
        state.getAndAdd(0x10001)
        worker.active = true
        LockSupport.unpark(worker)
      }
    }
  }

  private[this] def submitBlocking(runnable: Runnable)(implicit unsafe: Unsafe): Boolean =
    Blocking.blockingExecutor.submit(runnable)
}

private object ZScheduler {
  private val poolSize = java.lang.Runtime.getRuntime.availableProcessors

  def markCurrentWorkerAsBlocking(): Unit = {
    val worker = workerOrNull()
    if (worker ne null) {
      worker.markAsBlocking()
    } else {
      ()
    }
  }

  /**
   * If the current thread is a [[ZScheduler.Worker]] then it is returned,
   * otherwise returns null
   */
  private def workerOrNull(): ZScheduler.Worker =
    Thread.currentThread() match {
      case w: ZScheduler.Worker => w
      case _                    => null
    }

  /**
   * `Locations` tracks the number of observations of a fiber forked from a
   * location.
   */
  private sealed abstract class Locations {

    /**
     * Returns the number of observations of a fiber forked from the specified
     * location.
     */
    def get(trace: Trace): Long

    /**
     * Tracks a new observation of a fiber forked from the specified location
     * and returns the previous number of observations of a fiber forked from
     * that location.
     */
    def put(trace: Trace): Long
  }

  private object Locations {

    final class Enabled(sizeHint: Int = 64) extends Locations {
      private[this] val locations = mutable.HashMap.empty[Trace, AtomicLong]
      locations.sizeHint(sizeHint)

      def get(trace: Trace): Long = {
        val v = locations.getOrElse(trace, null)
        if (v eq null) 0L else v.get()
      }

      def put(trace: Trace): Long =
        locations.getOrElseUpdate(trace, new AtomicLong(0L)).getAndIncrement()
    }

    object Disabled extends Locations {
      def get(trace: Trace): Long = 0L
      def put(trace: Trace): Long = 0L
    }
  }

  /**
   * A `Supervisor` is a `Thread` that is responsible for monitoring workers and
   * shifting tasks from workers that are blocking to new workers.
   */
  private sealed abstract class Supervisor extends Thread

  /**
   * A `Worker` is a `Thread` that is responsible for executing actions
   * submitted to the scheduler.
   */
  private sealed abstract class Worker extends Thread {

    val submittedLocations: Locations

    /**
     * Whether this worker is currently active.
     */
    @volatile
    var active: Boolean =
      true

    /**
     * Whether this worker is currently blocking.
     */
    @volatile
    var blocking: Boolean =
      false

    /**
     * The current task being executed by this worker.
     */
    @volatile
    var currentRunnable: Runnable =
      null

    /**
     * The local work queue for this worker.
     */
    val localQueue: RingBufferPow2[Runnable] =
      RingBufferPow2[Runnable](256)

    /**
     * An optional field providing fast access to the next task to be executed
     * by this worker.
     */
    var nextRunnable: Runnable =
      null

    /**
     * The number of tasks that have been executed by this worker.
     */
    @volatile
    var opCount: Long =
      0L

    def markAsBlocking(): Unit

    final def setName(i: Int): Unit =
      setName(s"ZScheduler-Worker-$i")
  }
}
