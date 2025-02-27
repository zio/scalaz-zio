package zio.test

import zio.{Ref, ZIO}

private[test] object TestDebugFileLock {
  val make: ZIO[Any, Nothing, TestDebugFileLock] =
    Ref.Synchronized.make[Unit](()).map(TestDebugFileLock(_))
}

private[test] case class TestDebugFileLock(lock: Ref.Synchronized[Unit]) {
  def updateFile(action: ZIO[Any, Nothing, Unit]) =
    lock.updateZIO(_ => action)
}
