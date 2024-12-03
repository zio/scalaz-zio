package zio.internal

import zio._
import zio.test._
import zio.test.Assertion._
import zio.NIOScheduler.CancelToken

object NIOSchedulerSpec extends DefaultRunnableSpec {
  def spec = suite("NIOSchedulerSpec")(
    test("schedule a task to run after a delay") {
      for {
        group <- NIOScheduler.createAsynchronousChannelGroup(4)
        scheduler = NIOScheduler.fromAsynchronousChannelGroup(group)
        result <- Promise.make[Throwable, String]
        cancelToken = scheduler.schedule(new Runnable {
          def run(): Unit = result.succeed("Task executed!")
        }, zio.Duration.Finite(100.millis))
        _ <- ZIO.sleep(200.millis) // Ensure enough time for task to execute
        message <- result.await
      } yield assert(message)(equalTo("Task executed!"))
    },
    test("cancel a scheduled task") {
      for {
        group <- NIOScheduler.createAsynchronousChannelGroup(4)
        scheduler = NIOScheduler.fromAsynchronousChannelGroup(group)
        result <- Promise.make[Throwable, String]
        cancelToken = scheduler.schedule(new Runnable {
          def run(): Unit = result.succeed("Task executed!")
        }, zio.Duration.Finite(100.millis))
        _ = cancelToken() // Cancel the task immediately
        _ <- ZIO.sleep(200.millis) // Wait to see if the task executes
        message <- result.await
      } yield assert(message)(equalTo("Task executed!")).negated // Expect the task not to execute
    }
  )
}
