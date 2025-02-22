package zio

import zio.internal.FiberRuntime
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic.AtomicReference

trait ThreadLocalBridge {
  def makeFiberRef[A](initialValue: A)(link: A => Unit): ZIO[Scope, Nothing, FiberRef[A]]
}

object ThreadLocalBridge {
  private implicit val trace: Trace = Trace.empty

  def makeFiberRef[A](initialValue: A)(link: A => Unit): ZIO[Scope with ThreadLocalBridge, Nothing, FiberRef[A]] =
    ZIO.serviceWithZIO[ThreadLocalBridge](_.makeFiberRef(initialValue)(link))

  val live: ZLayer[Any, Nothing, ThreadLocalBridge] = ZLayer.suspend {
    val supervisor      = new FiberRefTrackingSupervisor
    val supervisorLayer = Runtime.addSupervisor(supervisor)
    val bridgeLayer = ZLayer.succeed {
      new ThreadLocalBridge {
        def makeFiberRef[A](initialValue: A)(link: A => Unit): ZIO[Scope, Nothing, FiberRef[A]] =
          for {
            fiberRef <- FiberRef.make(initialValue)
            _         = link(initialValue)
            _         = supervisor.trackFiberRef(fiberRef, link)(Unsafe.unsafe)
            _ <- Scope.addFinalizer(
                   ZIO.succeed {
                     link(initialValue)
                     supervisor.forgetFiberRef(fiberRef, link)(Unsafe.unsafe)
                   }
                 )
          } yield {
            new TrackingFiberRef(fiberRef, link)
          }
      }
    }
    supervisorLayer ++ bridgeLayer
  }

  private class FiberRefTrackingSupervisor extends Supervisor[Unit] {

    private val trackedRefs: Ref.Atomic[Set[(FiberRef[_], Any => Unit)]] =
      Ref.unsafe.make(Set.empty[(FiberRef[_], Any => Unit)])(Unsafe)

    override def value(implicit trace: Trace): UIO[Unit] = ZIO.unit

    override def onEnd[R, E, A1](value: Exit[E, A1], fiber: Fiber.Runtime[E, A1])(implicit unsafe: Unsafe): Unit =
      ()

    override def onStart[R, E, A1](
      environment: ZEnvironment[R],
      effect: ZIO[R, E, A1],
      parent: Option[Fiber.Runtime[Any, Any]],
      fiber: Fiber.Runtime[E, A1]
    )(implicit unsafe: Unsafe): Unit = ()

    def trackFiberRef[B](fiberRef: FiberRef[B], link: B => Unit)(implicit unsafe: Unsafe): Unit =
      trackedRefs.unsafe.update(old => old + ((fiberRef, link.asInstanceOf[Any => Unit])))

    def forgetFiberRef[B](fiberRef: FiberRef[B], link: B => Unit)(implicit unsafe: Unsafe): Unit =
      trackedRefs.unsafe.update(old => old - ((fiberRef, link.asInstanceOf[Any => Unit])))

    override def onSuspend[E, A1](fiber: Fiber.Runtime[E, A1])(implicit unsafe: Unsafe): Unit =
      foreachTrackedRef { (fiberRef, link) =>
        link(fiberRef.initial)
      }

    override def onResume[E, A1](fiber: Fiber.Runtime[E, A1])(implicit unsafe: Unsafe): Unit =
      foreachTrackedRef { (fiberRef, link) =>
        val value = fiber.asInstanceOf[FiberRuntime[E, A1]].getFiberRef(fiberRef)
        link(value)
      }

    private def foreachTrackedRef(f: (FiberRef[_], Any => Unit) => Unit)(implicit unsafe: Unsafe): Unit =
      trackedRefs.unsafe.get.foreach { case (fiberRef, link) =>
        f(fiberRef, link)
      }
  }

  class TrackingFiberRef[A](fiberRef: FiberRef[A], link: A => Unit) extends FiberRef.Proxy[A](fiberRef) {

    override def locally[R, EC >: Nothing, C](value: A)(use: ZIO[R, EC, C])(implicit trace: Trace): ZIO[R, EC, C] =
      fiberRef.get.flatMap { before =>
        fiberRef.locally(value) {
          (linkM(value) *> use)
            .ensuring(linkM(before))
        }
      }

    override def modify[B](f: A => (B, A))(implicit trace: Trace): UIO[B] =
      for {
        b    <- fiberRef.modify(f)
        newA <- fiberRef.get
        _    <- linkM(newA)
      } yield b

    private def linkM(a: A) =
      ZIO.succeed(link(a))
  }

}
