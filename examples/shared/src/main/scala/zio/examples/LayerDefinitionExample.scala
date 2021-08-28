package zio.examples
import zio._

object LayerDefinitionExample extends App {
  trait Foo {
    def bar: UIO[Unit]
  }

  object Foo {
    val live: URLayer[Has[Console] with Has[String] with Has[Int], Has[Foo]] =
      (FooLive.apply _).toLayer

    case class FooLive(console: Console, string: String, int: Int) extends Foo {
      override def bar: UIO[Unit] = console.printLine(s"$string and $int").orDie
    }
  }

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {

    val program: ZIO[Has[Foo], Nothing, Unit] = ZIO.serviceWith[Foo](_.bar)

    program
      .inject(
        Console.live,
        ZLayer.succeed("Hello"),
        ZLayer.succeed(3),
        Foo.live
      )
      .exitCode
  }

}
