package zio

import zio._
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.duration._
import zio.NIOScheduler.CancelToken

abstract class NIOScheduler {
  def schedule(task: Runnable, duration: zio.Duration)(implicit unsafe: Unsafe): CancelToken
}

object NIOScheduler {
  type CancelToken = () => Boolean

  // Define the event loop
  class EventLoop(executor: java.util.concurrent.ExecutorService) {
    private val queue = new java.util.concurrent.LinkedBlockingQueue[Runnable]()

    def start(): Unit = {
      new Thread(() => {
        while (!Thread.interrupted()) {
          val task = queue.take() // Non-blocking task execution from the queue
          task.run()
        }
      }).start()
    }

    def submit(task: Runnable): Unit = {
      queue.offer(task) // Submit task to the event loop
    }

    def shutdown(): Unit = {
      executor.shutdownNow()
    }
  }

  // Scheduler using a ScheduledExecutorService
  def fromScheduledExecutorService(service: ScheduledExecutorService): NIOScheduler =
    new NIOScheduler {
      val ConstFalse: CancelToken = () => false

      override def schedule(task: Runnable, duration: zio.Duration)(implicit unsafe: Unsafe): CancelToken =
        duration match {
          case d if d == zio.Duration.Infinity => ConstFalse
          case d if d == zio.Duration.Zero =>
            try task.run()
            catch {
              case ex: Throwable => throw new RuntimeException(s"Task execution failed: ${ex.getMessage}", ex)
            }
            ConstFalse
          case d: zio.Duration =>
            val future = service.schedule(
              new Runnable {
                override def run(): Unit =
                  try task.run()
                  catch {
                    case ex: Throwable =>
                      throw new RuntimeException(s"Scheduled task execution failed: ${ex.getMessage}", ex)
                  }
              },
              d.toNanos,
              TimeUnit.NANOSECONDS
            )
            () => {
              val wasCancelled = future.cancel(true)
              wasCancelled
            }
        }
    }

  // Scheduler using AsynchronousChannelGroup
  def fromAsynchronousChannelGroup(group: AsynchronousChannelGroup): NIOScheduler =
    new NIOScheduler {
      val ConstFalse: CancelToken = () => false

      override def schedule(task: Runnable, duration: zio.Duration)(implicit unsafe: Unsafe): CancelToken =
        duration match {
          case d if d == zio.Duration.Infinity => ConstFalse
          case d if d == zio.Duration.Zero =>
            task.run()
            ConstFalse
          case d: zio.Duration =>
            val executor = Executors.newSingleThreadExecutor()
            executor.execute(new Runnable {
              def run(): Unit = {
                Thread.sleep(d.toMillis)
                task.run()
              }
            })
            () => {
              executor.shutdownNow()
              true
            }
        }
    }

  // Helper method to create an AsynchronousChannelGroup
  def createAsynchronousChannelGroup(threadPoolSize: Int): ZIO[Any, Throwable, AsynchronousChannelGroup] = {
    ZIO.attempt(AsynchronousChannelGroup.withFixedThreadPool(threadPoolSize, Executors.defaultThreadFactory()))
  }

  // Helper method to create an event loop
  def createEventLoop(threadPoolSize: Int): ZIO[Any, Throwable, EventLoop] = {
    ZIO.attempt {
      val executorService = Executors.newFixedThreadPool(threadPoolSize)
      val eventLoop = new EventLoop(executorService)
      eventLoop.start()
      eventLoop
    }
  }
}
