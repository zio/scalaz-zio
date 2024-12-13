package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.duration.Duration
import java.nio.channels.{Selector, SelectionKey, SelectableChannel}
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import zio.stm.TQueue

class NIOScheduler(private val selector: Selector, private val executor: ScheduledExecutorService) extends Scheduler {
  private val taskQueue: TQueue[Runnable] = TQueue.unbounded.commit

  private val selectorThread: Thread = new Thread(new Runnable {
    override def run(): Unit = {
      try {
        while (true) {
          selector.select()
          val selectedKeys = selector.selectedKeys().iterator()
          while (selectedKeys.hasNext) {
            val key = selectedKeys.next()
            selectedKeys.remove()
            key.attachment().asInstanceOf[Runnable].run()
          }
        }
      } catch {
        case e: Exception => e.printStackTrace()
      }
    }
  })

  selectorThread.setName("NIOSelectorThread")
  selectorThread.start()

  override def asScheduledExecutorService: ScheduledExecutorService = executor

  override def schedule(task: Runnable, duration: Duration)(implicit unsafe: Unsafe): CancelToken = {
    if (duration.isZero || duration.isNegative) {
      task.run()
      () => false
    } else {
      val future = executor.schedule(
        new Runnable {
          override def run(): Unit = {
            taskQueue.offer(task).commit
            selector.wakeup()
          }
        },
        duration.toNanos,
        TimeUnit.NANOSECONDS
      )

      () => future.cancel(true)
    }
  }

  def registerChannel(channel: SelectableChannel, key: Int, handler: Runnable): ZIO[Any, Throwable, Unit] = {
    ZIO.effectTotal {
      channel.configureBlocking(false)
      channel.register(selector, key, handler)
    }.unit
  }

  override def shutdown(): ZIO[Any, Nothing, Unit] = {
    ZIO.effectTotal {
      selector.wakeup()
      selector.close()
      executor.shutdown()
    }.unit
  }

  private def drainTaskQueue(): ZIO[Any, Nothing, Unit] = {
    ZIO.effectAsync { callback =>
      while (true) {
        val task = taskQueue.poll.commit
        task.foreach(_.run())
      }
      callback(ZIO.unit)
    }
  }
}

object NIOScheduler {
  def make(numThreads: Int): ZManaged[Any, Throwable, NIOScheduler] = {
    ZManaged.make {
      for {
        selector <- ZIO.effect(Selector.open())
        executor <- ZIO.effect(Executors.newScheduledThreadPool(numThreads))
      } yield new NIOScheduler(selector, executor)
    } { scheduler =>
      scheduler.shutdown
    }
  }
}
