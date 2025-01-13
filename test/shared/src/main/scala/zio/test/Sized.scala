package zio.test

import zio._
import zio.stream.ZStream
import zio.stacktracer.TracingImplicits.disableAutoTrace

trait Sized extends Serializable {
  def size(implicit trace: Trace): UIO[Int]
  def withSize[R, E, A](size: Int)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
  def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Trace): Gen[R, A]
}

object Sized {

  val tag: Tag[Sized] = Tag[Sized]

  final case class Test(fiberRef: FiberRef[Int]) extends Sized {
    def size(implicit trace: Trace): UIO[Int] =
      fiberRef.get
    def withSize[R, E, A](size: Int)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      fiberRef.locally(size)(zio)
    def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Trace): Gen[R, A] =
      Gen {
        ZStream
          .fromZIO(fiberRef.get)
          .flatMap { oldSize =>
            ZStream.scoped(fiberRef.locallyScoped(size)) *> gen.sample.mapZIO(a => fiberRef.set(oldSize).as(a))
          }
      }
  }

  val default: ZLayer[Any, Nothing, Sized] =
    live(100)(Trace.empty)

  def live(size: Int)(implicit trace: Trace): Layer[Nothing, Sized] =
    ZLayer.scoped {
      val fiberRef = FiberRef.unsafe.make(size)(Unsafe)
      val sized    = Test(fiberRef)
      withSizedScoped(sized).as(sized)
    }

  private[test] val initial: Sized =
    Test(FiberRef.unsafe.make(100)(Unsafe.unsafe))

  def size(implicit trace: Trace): UIO[Int] =
    sizedWith(_.size)

  def withSize[R, E, A](size: Int)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    sizedWith(_.withSize(size)(zio))

  def withSizeGen[R, A](size: Int)(gen: Gen[R, A])(implicit trace: Trace): Gen[R, A] =
    Gen.fromZIO(sized).flatMap(_.withSizeGen(size)(gen))
}
