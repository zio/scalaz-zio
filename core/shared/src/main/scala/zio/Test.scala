package zio

object Test extends App {
  def call(): Unit = subcall()
  def subcall(): Unit =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(subcall2()).getOrThrowFiberFailure()
    }
  def subcall2() = ZIO.fail("boom")
  call()
}
