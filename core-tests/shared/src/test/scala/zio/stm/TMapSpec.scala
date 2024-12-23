package zio.stm

import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, nonFlaky}
import zio.test._
import zio.{Chunk, ZIO, ZIOBaseSpec}

object TMapSpec extends ZIOBaseSpec {

  def spec = suite("TMap")(
    suite("factories")(
      test("apply") {
        val tx = TMap.make("a" -> 1, "b" -> 2, "c" -> 2, "b" -> 3).flatMap[Any, Nothing, List[(String, Int)]](_.toList)
        assertZIO(tx.commit)(hasSameElements(List("a" -> 1, "b" -> 3, "c" -> 2)))
      },
      test("empty") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, List[(String, Int)]](_.toList)
        assertZIO(tx.commit)(isEmpty)
      },
      test("fromIterable") {
        val tx = TMap
          .fromIterable(List("a" -> 1, "b" -> 2, "c" -> 2, "b" -> 3))
          .flatMap[Any, Nothing, List[(String, Int)]](_.toList)
        assertZIO(tx.commit)(hasSameElements(List("a" -> 1, "b" -> 3, "c" -> 2)))
      }
    ),
    suite("lookups")(
      test("get existing element") {
        val tx = TMap.make("a" -> 1, "b" -> 2).flatMap[Any, Nothing, Option[Int]](_.get("a"))
        assertZIO(tx.commit)(isSome(equalTo(1)))
      },
      test("get non-existing element") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, Option[Int]](_.get("a"))
        assertZIO(tx.commit)(isNone)
      },
      test("getOrElse existing element") {
        val tx = TMap.make("a" -> 1, "b" -> 2).flatMap[Any, Nothing, Int](_.getOrElse("a", 10))
        assertZIO(tx.commit)(equalTo(1))
      },
      test("getOrElse non-existing element") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, Int](_.getOrElse("a", 10))
        assertZIO(tx.commit)(equalTo(10))
      },
      test("getOrElseSTM existing element") {
        val tx = TMap.make("a" -> 1, "b" -> 2).flatMap[Any, Nothing, Int](_.getOrElseSTM("a", STM.succeed(10)))
        assertZIO(tx.commit)(equalTo(1))
      },
      test("getOrElseSTM non-existing element") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, Int](_.getOrElseSTM("a", STM.succeed(10)))
        assertZIO(tx.commit)(equalTo(10))
      },
      test("contains existing element") {
        val tx = TMap.make("a" -> 1, "b" -> 2).flatMap[Any, Nothing, Boolean](_.contains("a"))
        assertZIO(tx.commit)(isTrue)
      },
      test("contains non-existing element") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, Boolean](_.contains("a"))
        assertZIO(tx.commit)(isFalse)
      },
      test("collect all elements") {
        val tx = TMap.make("a" -> 1, "b" -> 2, "c" -> 3).flatMap[Any, Nothing, List[(String, Int)]](_.toList)
        assertZIO(tx.commit)(hasSameElements(List("a" -> 1, "b" -> 2, "c" -> 3)))
      },
      test("collect all keys") {
        val tx = TMap.make("a" -> 1, "b" -> 2, "c" -> 3).flatMap[Any, Nothing, List[String]](_.keys)
        assertZIO(tx.commit)(hasSameElements(List("a", "b", "c")))
      },
      test("collect all values") {
        val tx = TMap.make("a" -> 1, "b" -> 2, "c" -> 3).flatMap[Any, Nothing, List[Int]](_.values)
        assertZIO(tx.commit)(hasSameElements(List(1, 2, 3)))
      }
    ),
    suite("insertion and removal")(
      test("add new element") {
        val tx =
          for {
            tmap <- TMap.empty[String, Int]
            _    <- tmap.put("a", 1)
            e    <- tmap.get("a")
          } yield e

        assertZIO(tx.commit)(isSome(equalTo(1)))
      },
      test("overwrite existing element") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "b" -> 2)
            _    <- tmap.put("a", 10)
            e    <- tmap.get("a")
          } yield e

        assertZIO(tx.commit)(isSome(equalTo(10)))
      },
      test("remove existing element") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "b" -> 2)
            _    <- tmap.delete("a")
            e    <- tmap.get("a")
          } yield e

        assertZIO(tx.commit)(isNone)
      },
      test("remove non-existing element") {
        val tx =
          for {
            tmap <- TMap.empty[String, Int]
            _    <- tmap.delete("a")
            e    <- tmap.get("a")
          } yield e

        assertZIO(tx.commit)(isNone)
      },
      test("add many keys with negative hash codes") {
        val expected = (1 to 1000).map(i => HashContainer(-i) -> i).toList

        val tx =
          for {
            tmap <- TMap.empty[HashContainer, Int]
            _    <- STM.collectAll(expected.map(i => tmap.put(i._1, i._2)))
            e    <- tmap.toList
          } yield e

        assertZIO(tx.commit)(hasSameElements(expected))
      },
      test("putIfAbsent") {
        val expected = List("a" -> 1, "b" -> 2)

        val tx =
          for {
            tmap <- TMap.make("a" -> 1)
            _    <- tmap.putIfAbsent("b", 2)
            _    <- tmap.putIfAbsent("a", 10)
            e    <- tmap.toList
          } yield e

        assertZIO(tx.commit)(hasSameElements(expected))
      }
    ),
    suite("transformations")(
      test("size") {
        val elems = List("a" -> 1, "b" -> 2)
        val tx =
          for {
            tmap <- TMap.fromIterable(elems)
            size <- tmap.size
          } yield size

        assertZIO(tx.commit)(equalTo(2))
      },
      test("toList") {
        val elems = List("a" -> 1, "b" -> 2)
        val tx =
          for {
            tmap <- TMap.fromIterable(elems)
            list <- tmap.toList
          } yield list

        assertZIO(tx.commit)(hasSameElements(elems))
      },
      test("toChunk") {
        val elems = List("a" -> 1, "b" -> 2)
        val tx =
          for {
            tmap  <- TMap.fromIterable(elems)
            chunk <- tmap.toChunk
          } yield chunk.toList

        assertZIO(tx.commit)(hasSameElements(elems))
      },
      test("toMap") {
        val elems = Map("a" -> 1, "b" -> 2)
        val tx =
          for {
            tmap <- TMap.fromIterable(elems)
            map  <- tmap.toMap
          } yield map

        assertZIO(tx.commit)(equalTo(elems))
      },
      test("merge") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1)
            a    <- tmap.merge("a", 2)(_ + _)
            b    <- tmap.merge("b", 2)(_ + _)
          } yield (a, b)

        assertZIO(tx.commit)(equalTo((3, 2)))
      },
      test("mergeSTM") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1)
            a    <- tmap.mergeSTM("a", 2)((v1, v2) => STM.succeed(v1 + v2))
            b    <- tmap.mergeSTM("b", 2)((v1, v2) => STM.succeed(v1 + v2))
          } yield (a, b)

        assertZIO(tx.commit)(equalTo((3, 2)))
      },
      suite("retainIf")(
        test("retainIf") {
          val tx =
            for {
              tmap    <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
              removed <- tmap.retainIf((k, _) => k == "aa")
              a       <- tmap.contains("a")
              aa      <- tmap.contains("aa")
              aaa     <- tmap.contains("aaa")
            } yield (removed, a, aa, aaa)

          assertZIO(tx.commit)(equalTo((Chunk("aaa" -> 3, "a" -> 1), false, true, false)))
        },
        test("retainIfDiscard") {
          val tx =
            for {
              tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
              _    <- tmap.retainIfDiscard((k, _) => k == "aa")
              a    <- tmap.contains("a")
              aa   <- tmap.contains("aa")
              aaa  <- tmap.contains("aaa")
            } yield (a, aa, aaa)

          assertZIO(tx.commit)(equalTo((false, true, false)))
        }
      ),
      suite("removeIf")(
        test("removeIf") {
          val tx =
            for {
              tmap    <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
              removed <- tmap.removeIf((_, v) => v > 1)
              a       <- tmap.contains("a")
              aa      <- tmap.contains("aa")
              aaa     <- tmap.contains("aaa")
            } yield (removed, a, aa, aaa)

          assertZIO(tx.commit)(equalTo((Chunk("aaa" -> 3, "aa" -> 2), true, false, false)))
        },
        test("removeIfDiscard") {
          val tx =
            for {
              tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
              _    <- tmap.removeIfDiscard((k, _) => k == "aa")
              a    <- tmap.contains("a")
              aa   <- tmap.contains("aa")
              aaa  <- tmap.contains("aaa")
            } yield (a, aa, aaa)

          assertZIO(tx.commit)(equalTo((true, false, true)))
        }
      ),
      test("transform") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transform((k, v) => k.replaceAll("a", "b") -> v * 2)
            res  <- tmap.toList
          } yield res

        assertZIO(tx.commit)(hasSameElements(List("b" -> 2, "bb" -> 4, "bbb" -> 6)))
      },
      test("transform with keys with negative hash codes") {
        val tx =
          for {
            tmap <- TMap.make(HashContainer(-1) -> 1, HashContainer(-2) -> 2, HashContainer(-3) -> 3)
            _    <- tmap.transform((k, v) => HashContainer(k.i * -2) -> v * 2)
            res  <- tmap.toList
          } yield res

        assertZIO(tx.commit)(hasSameElements(List(HashContainer(2) -> 2, HashContainer(4) -> 4, HashContainer(6) -> 6)))
      },
      test("transform and shrink") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transform((_, v) => "key" -> v * 2)
            res  <- tmap.toList
          } yield res

        assertZIO(tx.commit)(hasSameElements(List("key" -> 6)))
      },
      test("transformSTM") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transformSTM((k, v) => STM.succeed(k.replaceAll("a", "b") -> v * 2))
            res  <- tmap.toList
          } yield res

        assertZIO(tx.commit)(hasSameElements(List("b" -> 2, "bb" -> 4, "bbb" -> 6)))
      },
      test("transformSTM and shrink") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transformSTM((_, v) => STM.succeed("key" -> v * 2))
            res  <- tmap.toList
          } yield res

        assertZIO(tx.commit)(hasSameElements(List("key" -> 6)))
      },
      test("transformValues") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transformValues(_ * 2)
            res  <- tmap.toList
          } yield res

        assertZIO(tx.commit)(hasSameElements(List("a" -> 2, "aa" -> 4, "aaa" -> 6)))
      },
      test("parallel value transformation") {
        for {
          tmap <- TMap.make("a" -> 0).commit
          tx    = tmap.transformValues(_ + 1).commit.repeatN(999)
          n     = 2
          _    <- ZIO.collectAllParDiscard(List.fill(n)(tx))
          res  <- tmap.get("a").commit
        } yield assert(res)(isSome(equalTo(2000)))
      },
      test("transformValuesSTM") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transformValuesSTM(v => STM.succeed(v * 2))
            res  <- tmap.toList
          } yield res

        assertZIO(tx.commit)(hasSameElements(List("a" -> 2, "aa" -> 4, "aaa" -> 6)))
      },
      test("updateWith") {
        for {
          tmap <- TMap.make("a" -> 1, "b" -> 2)
          _    <- tmap.updateWith("a")(_.map(_ + 1))
          _    <- tmap.updateWith("b")(_ => None)
          _    <- tmap.updateWith("c")(_ => Some(3))
          _    <- tmap.updateWith("d")(_ => None)
          res  <- tmap.toMap
        } yield assertTrue(res == Map("a" -> 2, "c" -> 3))
      },
      test("updateWithSTM") {
        for {
          tmap <- TMap.make("a" -> 1, "b" -> 2)
          _    <- tmap.updateWithSTM("a")(v => STM.succeed(v.map(_ + 1)))
          _    <- tmap.updateWithSTM("b")(_ => STM.succeed(None))
          _    <- tmap.updateWithSTM("c")(_ => STM.succeed(Some(3)))
          _    <- tmap.updateWithSTM("d")(_ => STM.succeed(None))
          res  <- tmap.toMap
        } yield assertTrue(res == Map("a" -> 2, "c" -> 3))
      }
    ),
    suite("folds")(
      test("fold on non-empty map") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "b" -> 2, "c" -> 3)
            res  <- tmap.fold(0)((acc, kv) => acc + kv._2)
          } yield res

        assertZIO(tx.commit)(equalTo(6))
      },
      test("fold on empty map") {
        val tx =
          for {
            tmap <- TMap.empty[String, Int]
            res  <- tmap.fold(0)((acc, kv) => acc + kv._2)
          } yield res

        assertZIO(tx.commit)(equalTo(0))
      },
      test("foldSTM on non-empty map") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "b" -> 2, "c" -> 3)
            res  <- tmap.foldSTM(0)((acc, kv) => STM.succeed(acc + kv._2))
          } yield res

        assertZIO(tx.commit)(equalTo(6))
      },
      test("foldSTM on empty map") {
        val tx =
          for {
            tmap <- TMap.empty[String, Int]
            res  <- tmap.foldSTM(0)((acc, kv) => STM.succeed(acc + kv._2))
          } yield res

        assertZIO(tx.commit)(equalTo(0))
      }
    ),
    suite("bug #4648")(
      test("avoid NullPointerException caused by race condition") {
        for {
          keys <- ZIO.succeed((0 to 10).toList)
          map  <- TMap.fromIterable(keys.zipWithIndex).commit
          exit <- ZIO
                    .foreachDiscard(keys) { k =>
                      for {
                        _ <- map.delete(k).commit.fork
                        _ <- map.toChunk.commit
                      } yield ()
                    }
                    .exit
        } yield assert(exit)(succeeds(isUnit))
      }
    ),
    suite("concurrency")(
      test("modifying the same key is atomic") {
        val n = 1000
        for {
          map <- TMap.empty[String, Int].commit
          _ <- ZIO
                 .foreachParDiscard(1 to n) { _ =>
                   ZIO.yieldNow *>
                     (for {
                       i <- map.get("foo")
                       _ <- map.put("foo", i.fold(1)(_ + 1))
                     } yield ()).commit
                 }
                 .withParallelism(10)
          res <- map.get("foo").commit
        } yield assertTrue(res.contains(n))
      }
    ),
    suite("collection transformation transactions are idempotent")(
      test("toMap") {
        for {
          state <- TMap.empty[String, Int].commit
          a      = state.toMap.commit
          _     <- state.put("a", 2).commit
          s1    <- a
          _     <- state.delete("a").commit
          s2    <- a
        } yield assertTrue(
          s1 == Map("a" -> 2),
          s2 == Map.empty[String, Int]
        )
      },
      test("toList") {
        for {
          state <- TMap.empty[String, Int].commit
          a      = state.toList.commit
          _     <- state.put("a", 2).commit
          s1    <- a
          _     <- state.delete("a").commit
          s2    <- a
        } yield assertTrue(
          s1 == List("a" -> 2),
          s2 == List.empty[(String, Int)]
        )
      },
      test("toChunk") {
        for {
          state <- TMap.empty[String, Int].commit
          a      = state.toChunk.commit
          _     <- state.put("a", 2).commit
          s1    <- a
          _     <- state.delete("a").commit
          s2    <- a
        } yield assertTrue(
          s1 == Chunk("a" -> 2),
          s2 == Chunk.empty[(String, Int)]
        )
      }
    )
  ) @@ exceptJS(nonFlaky)

  private final case class HashContainer(val i: Int) {
    override def hashCode(): Int = i

    override def equals(obj: Any): Boolean =
      obj match {
        case o: HashContainer => i == o.i
        case _                => false
      }

    override def toString: String = s"HashContainer($i)"
  }
}
