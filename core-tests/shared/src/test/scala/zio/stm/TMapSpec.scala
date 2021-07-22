package zio.stm

import zio.test.Assertion._
import zio.test.TestAspect.nonFlaky
import zio.test._
import zio.{URIO, ZIO, ZIOBaseSpec}

object TMapSpec extends ZIOBaseSpec {

  def spec: ZSpec[Environment, Failure] = suite("TMap")(
    suite("factories")(
      test("apply") {
        val tx = TMap.make("a" -> 1, "b" -> 2, "c" -> 2, "b" -> 3).flatMap[Any, Nothing, List[(String, Int)]](_.toList)
        assertM(tx.commit)(hasSameElements(List("a" -> 1, "b" -> 3, "c" -> 2)))
      },
      test("empty") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, List[(String, Int)]](_.toList)
        assertM(tx.commit)(isEmpty)
      },
      test("fromIterable") {
        val tx = TMap
          .fromIterable(List("a" -> 1, "b" -> 2, "c" -> 2, "b" -> 3))
          .flatMap[Any, Nothing, List[(String, Int)]](_.toList)
        assertM(tx.commit)(hasSameElements(List("a" -> 1, "b" -> 3, "c" -> 2)))
      }
    ),
    suite("lookups")(
      test("get existing element") {
        val tx = TMap.make("a" -> 1, "b" -> 2).flatMap[Any, Nothing, Option[Int]](_.get("a"))
        assertM(tx.commit)(isSome(equalTo(1)))
      },
      test("get non-existing element") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, Option[Int]](_.get("a"))
        assertM(tx.commit)(isNone)
      },
      test("getOrElse existing element") {
        val tx = TMap.make("a" -> 1, "b" -> 2).flatMap[Any, Nothing, Int](_.getOrElse("a", 10))
        assertM(tx.commit)(equalTo(1))
      },
      test("getOrElse non-existing element") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, Int](_.getOrElse("a", 10))
        assertM(tx.commit)(equalTo(10))
      },
      test("contains existing element") {
        val tx = TMap.make("a" -> 1, "b" -> 2).flatMap[Any, Nothing, Boolean](_.contains("a"))
        assertM(tx.commit)(isTrue)
      },
      test("contains non-existing element") {
        val tx = TMap.empty[String, Int].flatMap[Any, Nothing, Boolean](_.contains("a"))
        assertM(tx.commit)(isFalse)
      },
      test("collect all elements") {
        val tx = TMap.make("a" -> 1, "b" -> 2, "c" -> 3).flatMap[Any, Nothing, List[(String, Int)]](_.toList)
        assertM(tx.commit)(hasSameElements(List("a" -> 1, "b" -> 2, "c" -> 3)))
      },
      test("collect all keys") {
        val tx = TMap.make("a" -> 1, "b" -> 2, "c" -> 3).flatMap[Any, Nothing, List[String]](_.keys)
        assertM(tx.commit)(hasSameElements(List("a", "b", "c")))
      },
      test("collect all values") {
        val tx = TMap.make("a" -> 1, "b" -> 2, "c" -> 3).flatMap[Any, Nothing, List[Int]](_.values)
        assertM(tx.commit)(hasSameElements(List(1, 2, 3)))
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

        assertM(tx.commit)(isSome(equalTo(1)))
      },
      test("overwrite existing element") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "b" -> 2)
            _    <- tmap.put("a", 10)
            e    <- tmap.get("a")
          } yield e

        assertM(tx.commit)(isSome(equalTo(10)))
      },
      test("remove existing element") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "b" -> 2)
            _    <- tmap.delete("a")
            e    <- tmap.get("a")
          } yield e

        assertM(tx.commit)(isNone)
      },
      test("remove non-existing element") {
        val tx =
          for {
            tmap <- TMap.empty[String, Int]
            _    <- tmap.delete("a")
            e    <- tmap.get("a")
          } yield e

        assertM(tx.commit)(isNone)
      },
      test("add many keys with negative hash codes") {
        val expected = (1 to 1000).map(i => HashContainer(-i) -> i).toList

        val tx =
          for {
            tmap <- TMap.empty[HashContainer, Int]
            _    <- STM.collectAll(expected.map(i => tmap.put(i._1, i._2)))
            e    <- tmap.toList
          } yield e

        assertM(tx.commit)(hasSameElements(expected))
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

        assertM(tx.commit)(hasSameElements(expected))
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

        assertM(tx.commit)(equalTo(2))
      },
      test("toList") {
        val elems = List("a" -> 1, "b" -> 2)
        val tx =
          for {
            tmap <- TMap.fromIterable(elems)
            list <- tmap.toList
          } yield list

        assertM(tx.commit)(hasSameElements(elems))
      },
      test("toChunk") {
        val elems = List("a" -> 1, "b" -> 2)
        val tx =
          for {
            tmap  <- TMap.fromIterable(elems)
            chunk <- tmap.toChunk
          } yield chunk.toList

        assertM(tx.commit)(hasSameElements(elems))
      },
      test("toMap") {
        val elems = Map("a" -> 1, "b" -> 2)
        val tx =
          for {
            tmap <- TMap.fromIterable(elems)
            map  <- tmap.toMap
          } yield map

        assertM(tx.commit)(equalTo(elems))
      },
      test("merge") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1)
            a    <- tmap.merge("a", 2)(_ + _)
            b    <- tmap.merge("b", 2)(_ + _)
          } yield (a, b)

        assertM(tx.commit)(equalTo((3, 2)))
      },
      test("retainIf") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.retainIf((k, _) => k == "aa")
            a    <- tmap.contains("a")
            aa   <- tmap.contains("aa")
            aaa  <- tmap.contains("aaa")
          } yield (a, aa, aaa)

        assertM(tx.commit)(equalTo((false, true, false)))
      },
      test("removeIf") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.removeIf((k, _) => k == "aa")
            a    <- tmap.contains("a")
            aa   <- tmap.contains("aa")
            aaa  <- tmap.contains("aaa")
          } yield (a, aa, aaa)

        assertM(tx.commit)(equalTo((true, false, true)))
      },
      test("transform") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transform((k, v) => k.replaceAll("a", "b") -> v * 2)
            res  <- tmap.toList
          } yield res

        assertM(tx.commit)(hasSameElements(List("b" -> 2, "bb" -> 4, "bbb" -> 6)))
      },
      test("transform with keys with negative hash codes") {
        val tx =
          for {
            tmap <- TMap.make(HashContainer(-1) -> 1, HashContainer(-2) -> 2, HashContainer(-3) -> 3)
            _    <- tmap.transform((k, v) => HashContainer(k.i * -2) -> v * 2)
            res  <- tmap.toList
          } yield res

        assertM(tx.commit)(hasSameElements(List(HashContainer(2) -> 2, HashContainer(4) -> 4, HashContainer(6) -> 6)))
      },
      test("transform and shrink") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transform((_, v) => "key" -> v * 2)
            res  <- tmap.toList
          } yield res

        assertM(tx.commit)(hasSameElements(List("key" -> 6)))
      },
      test("transformSTM") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transformSTM((k, v) => STM.succeed(k.replaceAll("a", "b") -> v * 2))
            res  <- tmap.toList
          } yield res

        assertM(tx.commit)(hasSameElements(List("b" -> 2, "bb" -> 4, "bbb" -> 6)))
      },
      test("transformSTM and shrink") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transformSTM((_, v) => STM.succeed("key" -> v * 2))
            res  <- tmap.toList
          } yield res

        assertM(tx.commit)(hasSameElements(List("key" -> 6)))
      },
      test("transformValues") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transformValues(_ * 2)
            res  <- tmap.toList
          } yield res

        assertM(tx.commit)(hasSameElements(List("a" -> 2, "aa" -> 4, "aaa" -> 6)))
      },
      test("parallel value transformation") {
        for {
          tmap <- TMap.make("a" -> 0).commit
          tx    = tmap.transformValues(_ + 1).commit.repeatN(999)
          n     = 2
          _    <- URIO.collectAllParDiscard(List.fill(n)(tx))
          res  <- tmap.get("a").commit
        } yield assert(res)(isSome(equalTo(2000)))
      },
      test("transformValuesM") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "aa" -> 2, "aaa" -> 3)
            _    <- tmap.transformValuesSTM(v => STM.succeed(v * 2))
            res  <- tmap.toList
          } yield res

        assertM(tx.commit)(hasSameElements(List("a" -> 2, "aa" -> 4, "aaa" -> 6)))
      }
    ),
    suite("folds")(
      test("fold on non-empty map") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "b" -> 2, "c" -> 3)
            res  <- tmap.fold(0)((acc, kv) => acc + kv._2)
          } yield res

        assertM(tx.commit)(equalTo(6))
      },
      test("fold on empty map") {
        val tx =
          for {
            tmap <- TMap.empty[String, Int]
            res  <- tmap.fold(0)((acc, kv) => acc + kv._2)
          } yield res

        assertM(tx.commit)(equalTo(0))
      },
      test("foldSTM on non-empty map") {
        val tx =
          for {
            tmap <- TMap.make("a" -> 1, "b" -> 2, "c" -> 3)
            res  <- tmap.foldSTM(0)((acc, kv) => STM.succeed(acc + kv._2))
          } yield res

        assertM(tx.commit)(equalTo(6))
      },
      test("foldSTM on empty map") {
        val tx =
          for {
            tmap <- TMap.empty[String, Int]
            res  <- tmap.foldSTM(0)((acc, kv) => STM.succeed(acc + kv._2))
          } yield res

        assertM(tx.commit)(equalTo(0))
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
      } @@ nonFlaky
    )
  )

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
