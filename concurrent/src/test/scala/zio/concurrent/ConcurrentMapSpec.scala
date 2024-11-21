package zio.concurrent

import zio._
import zio.test._
import zio.test.Assertion._

object ConcurrentMapSpec extends ZIOSpecDefault {
  def spec =
    suite("ConcurrentMap")(
      suite("collectFirst")(
        test("returns Some of first result for which the partial function is defined") {
          for {
            map <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            res <- map.collectFirst { case (3, _) => "Three" }
          } yield assertTrue(res.get == "Three")
        },
        test("returns None when the partial function is defined for no values") {
          for {
            map <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            res <- map.collectFirst { case (4, _) => "Four" }
          } yield assertTrue(res.isEmpty)
        }
      ),
      suite("compute")(
        test("computes a new value") {
          for {
            map      <- ConcurrentMap.make(1 -> 100)
            computed <- map.compute(1, (key, value) => value.map(key + _))
            stored   <- map.get(1)
          } yield assert(computed)(isSome(equalTo(101))) && assert(computed)(equalTo(stored))
        },
        test("returns new value for a non-existing key") {
          for {
            map      <- ConcurrentMap.empty[Int, Int]
            computed <- map.compute(1, (_, _) => Some(100))
            stored   <- map.get(1)
          } yield assert(computed)(isSome(equalTo(100))) && assert(computed)(equalTo(stored))
        },
        test("removes the entry when remap returns None") {
          for {
            map      <- ConcurrentMap.empty[Int, Int]
            _        <- map.put(1, 1000)
            computed <- map.compute(1, (_, _) => None)
            stored   <- map.get(1)
          } yield assert(computed)(isNone) && assert(stored)(isNone)
        }
      ),
      suite("computeIfAbsent")(
        test("computes a value of a non-existing key") {
          for {
            map      <- ConcurrentMap.empty[String, Int]
            computed <- map.computeIfAbsent("abc", _.length)
            stored   <- map.get("abc")
          } yield assert(computed)(equalTo(3)) && assert(stored)(isSome(equalTo(computed)))
        },
        test("preserves the existing bindings") {
          for {
            map      <- ConcurrentMap.make("abc" -> 3)
            computed <- map.computeIfAbsent("abc", _ => 10)
            stored   <- map.get("abc")
          } yield assert(computed)(equalTo(3)) && assert(stored)(isSome(equalTo(computed)))
        }
      ),
      suite("computeIfPresent")(
        test("computes a value of an existing key") {
          for {
            map      <- ConcurrentMap.make(1 -> 100)
            computed <- map.computeIfPresent(1, _ + _)
            stored   <- map.get(1)
          } yield assert(computed)(isSome(equalTo(101))) && assert(computed)(equalTo(stored))
        },
        test("returns None if key doesn't exist") {
          for {
            map      <- ConcurrentMap.empty[Int, String]
            computed <- map.computeIfPresent(1, (_, _) => "test")
            stored   <- map.get(1)
          } yield assert(computed)(isNone) && assert(computed)(equalTo(stored))
        }
      ),
      suite("constructors")(
        test("empty") {
          for {
            map   <- ConcurrentMap.empty[Int, String]
            items <- map.toChunk
          } yield assert(items)(isEmpty)
        },
        test("fromIterable") {
          for {
            data  <- ZIO.succeed(Chunk(1 -> "a", 2 -> "b", 3 -> "c"))
            map   <- ConcurrentMap.fromIterable(data)
            items <- map.toChunk
          } yield assert(items)(equalTo(data))
        },
        test("make") {
          for {
            data  <- ZIO.succeed(Chunk(1 -> "a", 2 -> "b", 3 -> "c"))
            map   <- ConcurrentMap.make(data: _*)
            items <- map.toChunk
          } yield assert(items)(equalTo(data))
        }
      ),
      suite("exists")(
        test("returns true when element which satisfies given predicate exists within the map") {
          for {
            map    <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            exists <- map.exists((k, _) => k % 2 == 0)
          } yield assertTrue(exists)
        },
        test("returns false when no elements in the map satisfy the predicate") {
          for {
            map    <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            exists <- map.exists((k, _) => k == 4)
          } yield assertTrue(!exists)
        }
      ),
      suite("fold")(
        test("returns the sum of the map's values") {
          for {
            map <- ConcurrentMap.make(("A", 1), ("B", 2), ("C", 3))
            res <- map.fold(0) { case (acc, (_, value)) => acc + value }
          } yield assertTrue(res == 6)
        }
      ),
      suite("forall")(
        test("returns true when predicate holds for all elements of the map") {
          for {
            map    <- ConcurrentMap.make(("A", 1), ("B", 2), ("C", 3))
            result <- map.forall((_, v) => v < 4)
          } yield assertTrue(result)
        },
        test("returns false when predicate fails for any element of the map") {
          for {
            map    <- ConcurrentMap.make(("A", 1), ("B", 2), ("C", 3))
            result <- map.forall((_, v) => v < 3)
          } yield assertTrue(!result)
        }
      ),
      suite("get")(
        test("retrieves an existing key") {
          for {
            map <- ConcurrentMap.make(1 -> "a", 2 -> "b")
            res <- map.get(1)
          } yield assert(res)(isSome(equalTo("a")))
        },
        test("returns None when retrieving a non-existing key") {
          for {
            map <- ConcurrentMap.empty[Int, String]
            res <- map.get(1)
          } yield assert(res)(isNone)
        }
      ),
      suite("put")(
        test("associates the non-existing key with a given value") {
          for {
            map    <- ConcurrentMap.empty[Int, String]
            putRes <- map.put(1, "a")
            getRes <- map.get(1)
          } yield assert(putRes)(isNone) && assert(getRes)(isSome(equalTo("a")))
        },
        test("overrides the existing mappings") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            putRes <- map.put(1, "b")
            getRes <- map.get(1)
          } yield assert(putRes)(isSome(equalTo("a"))) && assert(getRes)(isSome(equalTo("b")))
        }
      ),
      suite("putIfAbsent")(
        test("associates the non-existing key with a given value") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            putRes <- map.putIfAbsent(2, "b")
            getRes <- map.get(2)
          } yield assert(putRes)(isNone) && assert(getRes)(isSome(equalTo("b")))
        },
        test("preserves the existing mappings") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            putRes <- map.putIfAbsent(1, "b")
            getRes <- map.get(1)
          } yield assert(putRes)(isSome(equalTo("a"))) && assert(getRes)(isSome(equalTo("a")))
        }
      ),
      suite("putAll")(
        test("associates all non-existent keys with given value") {
          for {
            map  <- ConcurrentMap.empty[Int, String]
            _    <- map.putAll((1, "A"), (2, "B"), (3, "C"))
            resA <- map.get(1)
            resB <- map.get(2)
            resC <- map.get(3)
          } yield assertTrue(
            resA.get == "A",
            resB.get == "B",
            resC.get == "C"
          )
        }
      ),
      suite("isEmpty")(
        test("returns true for newly created empty map") {
          for {
            map <- ConcurrentMap.empty[Int, String]
            res <- map.isEmpty
          } yield assertTrue(res)
        },
        test("returns false for a map with entries") {
          for {
            map <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            res <- map.isEmpty
          } yield assertTrue(!res)
        },
        test("returns false for a map with all entries removed") {
          for {
            map <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            _   <- map.clear
            res <- map.isEmpty
          } yield assertTrue(res)
        }
      ),
      suite("remove")(
        test("returns the value associated with removed key") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            remRes <- map.remove(1)
            getRes <- map.get(1)
          } yield assert(remRes)(isSome(equalTo("a"))) && assert(getRes)(isNone)
        },
        test("returns None if map didn't contain the given key") {
          for {
            map    <- ConcurrentMap.empty[Int, String]
            remRes <- map.remove(1)
            getRes <- map.get(1)
          } yield assert(remRes)(isNone) && assert(getRes)(isNone)
        },
        test("succeeds if the key was mapped to the given value") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            remRes <- map.remove(1, "a")
            getRes <- map.get(1)
          } yield assert(remRes)(isTrue) && assert(getRes)(isNone)
        },
        test("fails if the key wasn't mapped to the given value") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            remRes <- map.remove(1, "b")
            getRes <- map.get(1)
          } yield assert(remRes)(isFalse) && assert(getRes)(isSome(equalTo("a")))
        }
      ),
      suite("removeIf")(
        test("removes the values that match a given predicate") {
          for {
            map  <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            _    <- map.removeIf((k, _) => k != 1)
            aRes <- map.get(1)
            bRes <- map.get(2)
            cRes <- map.get(3)
          } yield assertTrue(
            aRes.get == "A",
            bRes.isEmpty,
            cRes.isEmpty
          )
        }
      ),
      suite("clear")(
        test("removes all elements") {
          for {
            map <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            _   <- map.clear
            res <- map.toList
          } yield assertTrue(res == List.empty[(Int, String)])
        }
      ),
      suite("replace")(
        test("returns the replaced value") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            repRes <- map.replace(1, "b")
            getRes <- map.get(1)
          } yield assert(repRes)(isSome(equalTo("a"))) && assert(getRes)(isSome(equalTo("b")))
        },
        test("returns None if map didn't contain the given key") {
          for {
            map    <- ConcurrentMap.empty[Int, String]
            repRes <- map.replace(1, "b")
            getRes <- map.get(1)
          } yield assert(repRes)(isNone) && assert(getRes)(isNone)
        },
        test("succeeds if the key was mapped to the given value") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            repRes <- map.replace(1, "a", "b")
            getRes <- map.get(1)
          } yield assert(repRes)(isTrue) && assert(getRes)(isSome(equalTo("b")))
        },
        test("fails if the key wasn't mapped to the given value") {
          for {
            map    <- ConcurrentMap.make(1 -> "a")
            repRes <- map.replace(1, "b", "c")
            getRes <- map.get(1)
          } yield assert(repRes)(isFalse) && assert(getRes)(isSome(equalTo("a")))
        }
      ),
      suite("retainIf")(
        test("retains values that satisfy a given predicate") {
          for {
            map  <- ConcurrentMap.make((1, "A"), (2, "B"), (3, "C"))
            _    <- map.retainIf((k, _) => k == 1)
            aRes <- map.get(1)
            bRes <- map.get(2)
            cRes <- map.get(3)
          } yield assertTrue(
            aRes.get == "A",
            bRes.isEmpty,
            cRes.isEmpty
          )
        }
      )
    )
}
