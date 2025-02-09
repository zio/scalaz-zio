package zio

import zio.test.Assertion.isNull
import zio.test._

object ZIOSpecJVM extends ZIOBaseSpec {

  def spec = suite("ZIOSpecJVM")(
    suite("cooperative yielding") {
      test("cooperative yielding") {
        import java.util.concurrent._

        val executor = zio.Executor.fromJavaExecutor(Executors.newSingleThreadExecutor())

        val checkExecutor =
          ZIO.executor.flatMap(e => if (e != executor) ZIO.dieMessage("Executor is incorrect") else ZIO.unit)

        def infiniteProcess(ref: Ref[Int]): UIO[Nothing] =
          checkExecutor *> ref.update(_ + 1) *> infiniteProcess(ref)

        for {
          ref1   <- Ref.make(0)
          ref2   <- Ref.make(0)
          ref3   <- Ref.make(0)
          fiber1 <- infiniteProcess(ref1).onExecutor(executor).fork
          fiber2 <- infiniteProcess(ref2).onExecutor(executor).fork
          fiber3 <- infiniteProcess(ref3).onExecutor(executor).fork
          _      <- Live.live(ZIO.sleep(Duration.fromSeconds(1)))
          _      <- fiber1.interruptFork *> fiber2.interruptFork *> fiber3.interruptFork
          _      <- fiber1.await *> fiber2.await *> fiber3.await
          v1     <- ref1.get
          v2     <- ref2.get
          v3     <- ref3.get
        } yield assertTrue(v1 > 0 && v2 > 0 && v3 > 0)
      }
    },
    suite("fromAutoCloseable")(
      test("is null-safe") {
        // Will be `null` because the file doesn't exist
        def loadNonExistingFile = ZIO.attempt(this.getClass.getResourceAsStream(s"this_file_doesnt_exist.json"))

        for {
          shouldBeNull <- loadNonExistingFile
          // Should not fail when closing a null resource
          // The test will fail if the resource is not closed properly
          _ <- ZIO.fromAutoCloseable(loadNonExistingFile)
        } yield assert(shouldBeNull)(isNull)
      }
    )
  )
}
