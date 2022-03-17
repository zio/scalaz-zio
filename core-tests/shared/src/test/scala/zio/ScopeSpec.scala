package zio

import zio.test._

object ScopeSpec extends ZIOBaseSpec {

  def resource(id: Int)(ref: Ref[Chunk[Action]]): ZIO[Scope, Nothing, Int] =
    ZIO
      .uninterruptible(ref.update(_ :+ Action.Acquire(id)).as(id))
      .ensuring(Scope.addFinalizer(ref.update(_ :+ Action.Release(id))))

  def spec = suite("ScopeSpec")(
    suite("scoped")(
      test("runs finalizers when scope is closed") {
        for {
          ref <- Ref.make[Chunk[Action]](Chunk.empty)
          _ <- ZIO.scoped {
                 for {
                   resource <- resource(1)(ref)
                   _        <- ref.update(_ :+ Action.Use(resource))
                 } yield ()
               }
          actions <- ref.get
        } yield assertTrue(actions(0) == Action.Acquire(1)) &&
          assertTrue(actions(1) == Action.Use(1)) &&
          assertTrue(actions(2) == Action.Release(1))
      }
    ),
    suite("parallelFinalizers")(
      test("runs finalizers when scope is closed") {
        for {
          ref <- Ref.make[Chunk[Action]](Chunk.empty)
          _ <- ZIO.scoped {
                 for {
                   tuple <- ZIO.parallelFinalizers {
                              resource(1)(ref).zipPar(resource(2)(ref))
                            }
                   (resource1, resource2) = tuple
                   _                     <- ref.update(_ :+ Action.Use(resource1)).zipPar(ref.update(_ :+ Action.Use(resource2)))
                 } yield ()
               }
          actions <- ref.get.debug
        } yield assertTrue(actions.slice(0, 2).toSet == Set(Action.acquire(1), Action.acquire(2))) &&
          assertTrue(actions.slice(2, 4).toSet == Set(Action.use(1), Action.use(2))) &&
          assertTrue(actions.slice(4, 6).toSet == Set(Action.release(1), Action.release(2)))
      },
      test("runs finalizers in parallel") {
        for {
          promise <- Promise.make[Nothing, Unit]
          _ <- ZIO.scoped {
                 ZIO.parallelFinalizers {
                   ZIO.addFinalizer(promise.succeed(())) *> ZIO.addFinalizer(promise.await)
                 }
               }
        } yield assertCompletes
      }
    )
  )

  sealed trait Action

  object Action {
    final case class Acquire(id: Int) extends Action
    final case class Use(id: Int)     extends Action
    final case class Release(id: Int) extends Action

    def acquire(id: Int): Action =
      Action.Acquire(id)

    def use(id: Int): Action =
      Action.Use(id)

    def release(id: Int): Action =
      Action.Release(id)
  }

}
