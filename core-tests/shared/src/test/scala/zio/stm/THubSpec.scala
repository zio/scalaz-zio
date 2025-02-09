package zio.stm

import zio._
import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, nonFlaky}
import zio.test.{Gen, assert, check}

object THubSpec extends ZIOBaseSpec {

  val smallInt: Gen[Any, Int] =
    Gen.small(Gen.const(_), 1)

  def spec =
    suite("THubSpec")(
      suite("sequential publishers and subscribers")(
        test("with one publisher and one subscriber") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.bounded[Int](n).commit
              subscriber <- ZIO.scoped {
                              hub.subscribeScoped.flatMap { subscription =>
                                promise1.succeed(()) *> promise2.await *> ZIO.foreach(as.take(n))(_ =>
                                  subscription.take.commit
                                )
                              }
                            }.fork
              _      <- promise1.await
              _      <- ZIO.foreach(as.take(n))(a => hub.publish(a).commit)
              _      <- promise2.succeed(())
              values <- subscriber.join
            } yield assert(values)(equalTo(as.take(n)))
          }
        },
        test("with one publisher and two subscribers") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              promise3 <- Promise.make[Nothing, Unit]
              hub      <- THub.bounded[Int](n).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped
                    .flatMap(subscription =>
                      promise1.succeed(()) *> promise3.await *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                    )
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped
                    .flatMap(subscription =>
                      promise2.succeed(()) *> promise3.await *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                    )
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as.take(n))(a => hub.publish(a).commit)
              _       <- promise3.succeed(())
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1)(equalTo(as.take(n))) &&
              assert(values2)(equalTo(as.take(n)))
          }
        }
      ),
      suite("concurrent publishers and subscribers")(
        test("one to one") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise <- Promise.make[Nothing, Unit]
              hub     <- THub.bounded[Int](n).commit
              subscriber <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              _      <- promise.await
              _      <- ZIO.foreach(as.take(n))(a => hub.publish(a).commit).fork
              values <- subscriber.join
            } yield assert(values)(equalTo(as.take(n)))
          }
        },
        test("one to many") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.bounded[Int](n).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as.take(n))(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1)(equalTo(as.take(n))) &&
              assert(values2)(equalTo(as.take(n)))
          }
        },
        test("many to many") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.bounded[Int](n * 2).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach((as ::: as).take(n * 2))(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach((as ::: as).take(n * 2))(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as.take(n))(a => hub.publish(a).commit).fork
              _       <- ZIO.foreach(as.take(n).map(-_))(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1.filter(_ > 0))(equalTo(as.take(n))) &&
              assert(values1.filter(_ < 0))(equalTo(as.take(n).map(-_))) &&
              assert(values2.filter(_ > 0))(equalTo(as.take(n))) &&
              assert(values2.filter(_ < 0))(equalTo(as.take(n).map(-_)))
          }
        }
      ),
      suite("back pressure")(
        test("one to one") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise <- Promise.make[Nothing, Unit]
              hub     <- THub.bounded[Int](n).commit
              subscriber <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise.succeed(()) *> ZIO.foreach(as)(_ => subscription.take.commit)
                  }
                }.fork
              _      <- promise.await
              _      <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              values <- subscriber.join
            } yield assert(values)(equalTo(as))
          }
        },
        test("one to many") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.bounded[Int](n).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach(as)(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach(as)(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1)(equalTo(as)) &&
              assert(values2)(equalTo(as))
          }
        },
        test("many to many") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.bounded[Int](n * 2).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach((as ::: as))(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach((as ::: as))(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              _       <- ZIO.foreach(as.map(-_))(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1.filter(_ > 0))(equalTo(as)) &&
              assert(values1.filter(_ < 0))(equalTo(as.map(-_))) &&
              assert(values2.filter(_ > 0))(equalTo(as)) &&
              assert(values2.filter(_ < 0))(equalTo(as.map(-_)))
          }
        }
      ),
      suite("dropping")(
        test("one to one") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise <- Promise.make[Nothing, Unit]
              hub     <- THub.dropping[Int](n).commit
              subscriber <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              _      <- promise.await
              _      <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              values <- subscriber.join
            } yield assert(values)(equalTo(as.take(n)))
          }
        },
        test("one to many") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.dropping[Int](n).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1)(equalTo(as.take(n))) &&
              assert(values2)(equalTo(as.take(n)))
          }
        },
        test("many to many") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.dropping[Int](n * 2).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach((as ::: as).take(n * 2))(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach((as ::: as).take(n * 2))(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              _       <- ZIO.foreach(as.map(-_))(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(as)(startsWith(values1.filter(_ > 0))) &&
              assert(as.map(-_))(startsWith(values1.filter(_ < 0))) &&
              assert(as)(startsWith(values2.filter(_ > 0))) &&
              assert(as.map(-_))(startsWith(values2.filter(_ < 0)))
          }
        }
      ),
      suite("sliding")(
        test("one to one") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise <- Promise.make[Nothing, Unit]
              hub     <- THub.sliding[Int](n).commit
              subscriber <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              _         <- promise.await
              publisher <- ZIO.foreach(as.sorted)(a => hub.publish(a).commit).fork
              _         <- publisher.join
              values    <- subscriber.join
            } yield assert(values)(isSorted)
          }
        },
        test("one to many") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.sliding[Int](n).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach(as.take(n))(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as.sorted)(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1)(isSorted) &&
              assert(values2)(isSorted)
          }
        },
        test("many to many") {
          check(smallInt, Gen.listOf(smallInt)) { (n, as) =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.sliding[Int](n * 2).commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach((as ::: as).take(n * 2))(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach((as ::: as).take(n * 2))(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as.sorted)(a => hub.publish(a).commit).fork
              _       <- ZIO.foreach(as.map(-_).sorted)(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1.filter(_ > 0))(isSorted) &&
              assert(values1.filter(_ < 0))(isSorted) &&
              assert(values2.filter(_ > 0))(isSorted) &&
              assert(values2.filter(_ < 0))(isSorted)
          }
        }
      ),
      suite("unbounded")(
        test("one to one") {
          check(Gen.listOf(smallInt)) { as =>
            for {
              promise <- Promise.make[Nothing, Unit]
              hub     <- THub.unbounded[Int].commit
              subscriber <-
                hub.subscribe.commit.flatMap { subscription =>
                  promise.succeed(()) *> ZIO.foreach(as)(_ => subscription.take.commit)
                }.fork
              _      <- promise.await
              _      <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              values <- subscriber.join
            } yield assert(values)(equalTo(as))
          }
        },
        test("one to many") {
          check(Gen.listOf(smallInt)) { as =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.unbounded[Int].commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach(as)(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach(as)(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1)(equalTo(as)) &&
              assert(values2)(equalTo(as))
          }
        },
        test("many to many") {
          check(Gen.listOf(smallInt)) { as =>
            for {
              promise1 <- Promise.make[Nothing, Unit]
              promise2 <- Promise.make[Nothing, Unit]
              hub      <- THub.unbounded[Int].commit
              subscriber1 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise1.succeed(()) *> ZIO.foreach((as ::: as))(_ => subscription.take.commit)
                  }
                }.fork
              subscriber2 <-
                ZIO.scoped {
                  hub.subscribeScoped.flatMap { subscription =>
                    promise2.succeed(()) *> ZIO.foreach((as ::: as))(_ => subscription.take.commit)
                  }
                }.fork
              _       <- promise1.await
              _       <- promise2.await
              _       <- ZIO.foreach(as)(a => hub.publish(a).commit).fork
              _       <- ZIO.foreach(as.map(-_))(a => hub.publish(a).commit).fork
              values1 <- subscriber1.join
              values2 <- subscriber2.join
            } yield assert(values1.filter(_ > 0))(equalTo(as)) &&
              assert(values1.filter(_ < 0))(equalTo(as.map(-_))) &&
              assert(values2.filter(_ > 0))(equalTo(as)) &&
              assert(values2.filter(_ < 0))(equalTo(as.map(-_)))
          }
        }
      )
    ) @@ exceptJS(nonFlaky(20))
}
