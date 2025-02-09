/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.stm

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stm.ZSTM.RetryException
import zio.stm.ZSTM.internal._
import zio.{Chunk, ChunkBuilder, NonEmptyChunk, Unsafe}

/**
 * Transactional map implemented on top of [[TRef]] and [[TArray]]. Resolves
 * conflicts via chaining.
 */
final class TMap[K, V] private (
  private val tBuckets: TRef[TArray[List[(K, V)]]],
  private val tSize: TRef[Int]
) {

  /**
   * Lock used to avoid contention when resizing the array.
   */
  private[this] val resizeLock: ZSTMLockSupport.Lock = ZSTMLockSupport.Lock()

  /**
   * Tests whether or not map contains a key.
   */
  def contains(k: K): USTM[Boolean] =
    get(k).map(_.isDefined)

  /**
   * Tests if the map is empty or not
   */
  def isEmpty: USTM[Boolean] =
    tSize.get.map(_ == 0)

  /**
   * Removes binding for given key.
   */
  def delete(k: K): USTM[Unit] =
    ZSTM.Effect { (journal, _, _) =>
      val buckets = tBuckets.unsafeGet(journal)
      val idx     = TMap.indexOf(k, buckets.array.length)
      val bucket  = buckets.array(idx).unsafeGet(journal)

      val (toRemove, toRetain) = bucket.partition(_._1 == k)

      if (toRemove.nonEmpty) {
        val currSize = tSize.unsafeGet(journal)
        buckets.array(idx).unsafeSet(journal, toRetain)
        tSize.unsafeSet(journal, currSize - 1)
      }

      ()
    }

  /**
   * Deletes all entries associated with the specified keys.
   */
  def deleteAll(ks: Iterable[K]): USTM[Unit] =
    ZSTM.Effect { (journal, _, _) =>
      ks.foreach { k =>
        val buckets = tBuckets.unsafeGet(journal)
        val idx     = TMap.indexOf(k, buckets.array.length)
        val bucket  = buckets.array(idx).unsafeGet(journal)

        val (toRemove, toRetain) = bucket.partition(_._1 == k)

        if (toRemove.nonEmpty) {
          val currSize = tSize.unsafeGet(journal)
          buckets.array(idx).unsafeSet(journal, toRetain)
          tSize.unsafeSet(journal, currSize - 1)
        }
      }
    }

  /**
   * Finds the key/value pair matching the specified predicate, and uses the
   * provided function to extract a value out of it.
   */
  def find[A](pf: PartialFunction[(K, V), A]): USTM[Option[A]] =
    findSTM {
      case kv if pf.isDefinedAt(kv) => STM.succeedNow(pf(kv))
      case _                        => STM.fail(None)
    }

  /**
   * Finds the key/value pair matching the specified predicate, and uses the
   * provided effectful function to extract a value out of it.
   */
  def findSTM[R, E, A](f: (K, V) => ZSTM[R, Option[E], A]): ZSTM[R, E, Option[A]] =
    foldSTM[R, E, Option[A]](Option.empty[A]) {
      case (None, (k, v)) =>
        f(k, v).foldSTM(_.fold[STM[E, Option[A]]](STM.none)(STM.fail(_)), STM.some(_))
      case (other, _) => STM.succeedNow(other)
    }

  /**
   * Finds all the key/value pairs matching the specified predicate, and uses
   * the provided function to extract values out them.
   */
  def findAll[A](pf: PartialFunction[(K, V), A]): USTM[Chunk[A]] =
    findAllSTM {
      case kv if pf.isDefinedAt(kv) => STM.succeedNow(pf(kv))
      case _                        => STM.fail(None)
    }

  /**
   * Finds all the key/value pairs matching the specified predicate, and uses
   * the provided effectful function to extract values out of them..
   */
  def findAllSTM[R, E, A](pf: (K, V) => ZSTM[R, Option[E], A]): ZSTM[R, E, Chunk[A]] =
    foldSTM(Chunk.empty: Chunk[A]) { case (acc, kv) =>
      pf(kv._1, kv._2).foldSTM(
        {
          case None    => STM.succeedNow(acc)
          case Some(e) => STM.fail(e)
        },
        a => STM.succeedNow(acc :+ a)
      )
    }

  /**
   * Atomically folds using a pure function.
   */
  def fold[A](zero: A)(op: (A, (K, V)) => A): USTM[A] =
    ZSTM.Effect { (journal, _, _) =>
      val buckets = tBuckets.unsafeGet(journal)
      var res     = zero
      var i       = 0

      while (i < buckets.array.length) {
        val bucket = buckets.array(i)
        val items  = bucket.unsafeGet(journal)

        res = items.foldLeft(res)(op)

        i += 1
      }

      res
    }

  /**
   * Atomically folds using a transactional function.
   */
  def foldSTM[R, E, A](zero: A)(op: (A, (K, V)) => ZSTM[R, E, A]): ZSTM[R, E, A] =
    toChunk.flatMap(ZSTM.foldLeft(_)(zero)(op))

  /**
   * Atomically performs transactional-effect for each binding present in map.
   */
  def foreach[R, E](f: (K, V) => ZSTM[R, E, Unit]): ZSTM[R, E, Unit] =
    foldSTM(())((_, kv) => f(kv._1, kv._2))

  /**
   * Retrieves value associated with given key.
   */
  def get(k: K): USTM[Option[V]] =
    ZSTM.Effect { (journal, _, _) =>
      val buckets = tBuckets.unsafeGet(journal)
      val idx     = TMap.indexOf(k, buckets.array.length)
      val bucket  = buckets.array(idx).unsafeGet(journal)

      bucket.find(_._1 == k).map(_._2)
    }

  /**
   * Retrieves value associated with given key or default value, in case the key
   * isn't present.
   */
  def getOrElse(k: K, default: => V): USTM[V] =
    get(k).map(_.getOrElse(default))

  /**
   * Retrieves value associated with given key or transactional default value,
   * in case the key isn't present.
   */
  def getOrElseSTM[R, E](k: K, default: => ZSTM[R, E, V]): ZSTM[R, E, V] =
    get(k).flatMap(_.fold(default)(STM.succeedNow))

  /**
   * Collects all keys stored in map.
   */
  def keys: USTM[List[K]] =
    toList.map(_.map(_._1))

  /**
   * If the key `k` is not already associated with a value, stores the provided
   * value, otherwise merge the existing value with the new one using function
   * `f` and store the result
   */
  def merge(k: K, v: V)(f: (V, V) => V): USTM[V] =
    get(k).flatMap(_.fold(put(k, v).as(v)) { v0 =>
      val v1 = f(v0, v)
      put(k, v1).as(v1)
    })

  /**
   * If the key `k` is not already associated with a value, stores the provided
   * value, otherwise merge the existing value with the new one using
   * transactional function `f` and store the result
   */
  def mergeSTM[R, E](k: K, v: V)(f: (V, V) => ZSTM[R, E, V]): ZSTM[R, E, V] =
    get(k).flatMap {
      case None     => put(k, v).as(v)
      case Some(v0) => f(v0, v).flatMap(v1 => put(k, v1).as(v1))
    }

  /**
   * Stores new binding into the map.
   */
  def put(k: K, v: V): USTM[Unit] = {
    def resize(journal: Journal, buckets: TArray[List[(K, V)]]): Unit = {
      val capacity    = buckets.array.length
      val newCapacity = capacity << 1
      val newBuckets  = Array.fill[List[(K, V)]](newCapacity)(Nil)
      var i           = 0

      while (i < capacity) {
        val pairs = buckets.array(i).unsafeGet(journal)
        val it    = pairs.iterator

        while (it.hasNext) {
          val pair = it.next()
          val idx  = TMap.indexOf(pair._1, newCapacity)
          newBuckets(idx) = pair :: newBuckets(idx)
        }

        i += 1
      }

      // insert new pair
      val newIdx = TMap.indexOf(k, newCapacity)
      newBuckets(newIdx) = (k, v) :: newBuckets(newIdx)

      val newArray = Array.ofDim[TRef[List[(K, V)]]](newCapacity)

      i = 0
      while (i < newCapacity) {
        newArray(i) = TRef.unsafeMake(newBuckets(i))
        i += 1
      }

      tBuckets.unsafeSet(journal, new TArray(newArray))
    }

    ZSTM.Effect { (journal, _, _) =>
      val buckets      = tBuckets.unsafeGet(journal)
      val capacity     = buckets.array.length
      val idx          = TMap.indexOf(k, capacity)
      val bucket       = buckets.array(idx).unsafeGet(journal)
      val shouldUpdate = bucket.exists(_._1 == k)

      if (shouldUpdate) {
        val newBucket = bucket.map(kv => if (kv._1 == k) (k, v) else kv)
        buckets.array(idx).unsafeSet(journal, newBucket)
      } else {
        val newSize = tSize.unsafeGet(journal) + 1

        tSize.unsafeSet(journal, newSize)

        if (capacity * TMap.LoadFactor < newSize) {

          /**
           * Avoid contention of multiple threads all trying to resize the array
           * by retrying if another thread is already resizing it
           */
          val acquired = ZSTMLockSupport.tryLock(resizeLock) {
            resize(journal, buckets)
          }
          if (!acquired) throw RetryException
        } else {
          val newBucket = (k, v) :: bucket
          buckets.array(idx).unsafeSet(journal, newBucket)
        }
      }

      ()
    }
  }

  /**
   * Stores new binding in the map if it does not already exist.
   */
  def putIfAbsent(k: K, v: V): USTM[Unit] =
    get(k).flatMap(_.fold(put(k, v))(_ => STM.unit))

  /**
   * Removes bindings matching predicate and returns the removed entries.
   */
  def removeIf(p: (K, V) => Boolean): USTM[Chunk[(K, V)]] =
    ZSTM.Effect { (journal, _, _) =>
      val f        = p.tupled
      val buckets  = tBuckets.unsafeGet(journal)
      val capacity = buckets.array.length

      var i       = 0
      var newSize = 0
      val removed = ChunkBuilder.make[(K, V)]()

      while (i < capacity) {
        val bucket    = buckets.array(i).unsafeGet(journal)
        var newBucket = List.empty[(K, V)]

        val it = bucket.iterator
        while (it.hasNext) {
          val pair = it.next()
          if (!f(pair)) {
            newBucket = pair :: newBucket
            newSize += 1
          } else {
            removed += pair
          }
        }

        buckets.array(i).unsafeSet(journal, newBucket)
        i += 1
      }

      tSize.unsafeSet(journal, newSize)

      removed.result()
    }

  /**
   * Removes bindings matching predicate.
   */
  def removeIfDiscard(p: (K, V) => Boolean): USTM[Unit] =
    ZSTM.Effect { (journal, _, _) =>
      val f        = p.tupled
      val buckets  = tBuckets.unsafeGet(journal)
      val capacity = buckets.array.length

      var i       = 0
      var newSize = 0

      while (i < capacity) {
        val bucket    = buckets.array(i).unsafeGet(journal)
        var newBucket = List.empty[(K, V)]

        val it = bucket.iterator
        while (it.hasNext) {
          val pair = it.next()
          if (!f(pair)) {
            newBucket = pair :: newBucket
            newSize += 1
          }
        }

        buckets.array(i).unsafeSet(journal, newBucket)
        i += 1
      }

      tSize.unsafeSet(journal, newSize)

      ()
    }

  /**
   * Retains bindings matching predicate and returns removed bindings.
   */
  def retainIf(p: (K, V) => Boolean): USTM[Chunk[(K, V)]] =
    removeIf(!p(_, _))

  /**
   * Retains bindings matching predicate.
   */
  def retainIfDiscard(p: (K, V) => Boolean): USTM[Unit] =
    removeIfDiscard(!p(_, _))

  /**
   * Returns the number of bindings.
   */
  val size: USTM[Int] =
    tSize.get

  /**
   * Takes the first matching value, or retries until there is one.
   */
  def takeFirst[A](pf: PartialFunction[(K, V), A]): USTM[A] =
    ZSTM
      .Effect[Any, Nothing, Option[A]] { (journal, _, _) =>
        var result      = Option.empty[A]
        val size        = tSize.unsafeGet(journal)
        val buckets     = tBuckets.unsafeGet(journal)
        val capacity    = buckets.array.length
        val isDefinedAt = (t: (K, V)) => pf.isDefinedAt(t)

        var i = 0

        while (i < capacity && (result == None)) {
          val bucket   = buckets.array(i).unsafeGet(journal)
          val recreate = bucket.exists(isDefinedAt)

          if (recreate) {
            var newBucket = List.empty[(K, V)]
            val it        = bucket.iterator

            while (it.hasNext && (result == None)) {
              val pair = it.next()
              if (isDefinedAt(pair) && result == None) {
                result = Some(pf(pair))
              } else {
                newBucket = pair :: newBucket
              }
            }
            buckets.array(i).unsafeSet(journal, newBucket)
          }

          i += 1
        }

        if (result != None) tSize.unsafeSet(journal, size - 1)

        result
      }
      .collect { case Some(value) => value }

  def takeFirstSTM[R, E, A](pf: (K, V) => ZSTM[R, Option[E], A]): ZSTM[R, E, A] =
    findSTM { case (k, v) =>
      pf(k, v).map(a => k -> a)
    }.collect { case Some(value) =>
      value
    }.flatMap(kv => delete(kv._1).as(kv._2))

  /**
   * Takes all matching values, or retries until there is at least one.
   */
  def takeSome[A](pf: PartialFunction[(K, V), A]): USTM[NonEmptyChunk[A]] =
    ZSTM
      .Effect[Any, Nothing, Option[NonEmptyChunk[A]]] { (journal, _, _) =>
        val buckets      = tBuckets.unsafeGet(journal)
        val capacity     = buckets.array.length
        val chunkBuilder = ChunkBuilder.make[A]()
        val isDefinedAt  = (t: (K, V)) => pf.isDefinedAt(t)

        var i       = 0
        var newSize = 0

        while (i < capacity) {
          val bucket   = buckets.array(i).unsafeGet(journal)
          val recreate = bucket.exists(isDefinedAt)

          if (recreate) {
            var newBucket = List.empty[(K, V)]

            val it = bucket.iterator
            while (it.hasNext) {
              val pair = it.next()
              if (pf.isDefinedAt(pair)) {
                chunkBuilder += pf(pair)
              } else {
                newBucket = pair :: newBucket
                newSize += 1
              }
            }

            buckets.array(i).unsafeSet(journal, newBucket)
          } else {
            newSize += bucket.length
          }

          i += 1
        }

        tSize.unsafeSet(journal, newSize)

        NonEmptyChunk.fromChunk(chunkBuilder.result())
      }
      .collect { case Some(value) => value }

  /**
   * Takes all matching values, or retries until there is at least one.
   */
  def takeSomeSTM[R, E, A](pf: (K, V) => ZSTM[R, Option[E], A]): ZSTM[R, E, NonEmptyChunk[A]] =
    findAllSTM { case (k, v) =>
      pf(k, v).map(a => k -> a)
    }.map(NonEmptyChunk.fromChunk(_))
      .collect { case Some(value) =>
        value
      }
      .flatMap(both => deleteAll(both.map(_._1)).as(both.map(_._2)))

  /**
   * Collects all bindings into a list.
   */
  def toList: USTM[List[(K, V)]] =
    fold(List.empty[(K, V)])((acc, kv) => kv :: acc)

  /**
   * Collects all bindings into a chunk.
   */
  def toChunk: USTM[Chunk[(K, V)]] =
    ZSTM.Effect { (journal, _, _) =>
      val buckets  = tBuckets.unsafeGet(journal)
      val capacity = buckets.array.length
      val size     = tSize.unsafeGet(journal)
      var i        = 0
      val builder  = ChunkBuilder.make[(K, V)](size)

      while (i < capacity) {
        val bucket = buckets.array(i)

        builder ++= bucket.unsafeGet(journal)

        i += 1
      }

      builder.result()
    }

  /**
   * Collects all bindings into a map.
   */
  def toMap: USTM[Map[K, V]] = ZSTM.suspend {
    fold(Map.newBuilder[K, V])(_ += _).map(_.result())
  }

  /**
   * Atomically updates all bindings using a pure function.
   */
  def transform(f: (K, V) => (K, V)): USTM[Unit] =
    ZSTM.Effect { (journal, _, _) =>
      val g        = f.tupled
      val buckets  = tBuckets.unsafeGet(journal)
      val capacity = buckets.array.length

      val newBuckets = Array.fill[List[(K, V)]](capacity)(Nil)
      var newSize    = 0
      var i          = 0

      while (i < capacity) {
        val bucket = buckets.array(i)
        val pairs  = bucket.unsafeGet(journal)

        val it = pairs.iterator
        while (it.hasNext) {
          val newPair   = g(it.next())
          val idx       = TMap.indexOf(newPair._1, capacity)
          val newBucket = newBuckets(idx)

          if (!newBucket.exists(_._1 == newPair._1)) {
            newBuckets(idx) = newPair :: newBucket
            newSize += 1
          }
        }

        i += 1
      }

      i = 0

      while (i < capacity) {
        buckets.array(i).unsafeSet(journal, newBuckets(i))
        i += 1
      }

      tSize.unsafeSet(journal, newSize)

      ()
    }

  /**
   * Atomically updates all bindings using a transactional function.
   */
  def transformSTM[R, E](f: (K, V) => ZSTM[R, E, (K, V)]): ZSTM[R, E, Unit] =
    toChunk.flatMap { data =>
      val g = f.tupled

      ZSTM.foreach(data)(g).flatMap { newData =>
        ZSTM.Effect { (journal, _, _) =>
          val buckets    = tBuckets.unsafeGet(journal)
          val capacity   = buckets.array.length
          val newBuckets = Array.fill[List[(K, V)]](capacity)(Nil)
          var newSize    = 0

          val it = newData.iterator
          while (it.hasNext) {
            val newPair   = it.next()
            val idx       = TMap.indexOf(newPair._1, capacity)
            val newBucket = newBuckets(idx)

            if (!newBucket.exists(_._1 == newPair._1)) {
              newBuckets(idx) = newPair :: newBucket
              newSize += 1
            }
          }

          var i = 0
          while (i < capacity) {
            buckets.array(i).unsafeSet(journal, newBuckets(i))
            i += 1
          }

          tSize.unsafeSet(journal, newSize)
          ()
        }
      }
    }

  /**
   * Atomically updates all values using a pure function.
   */
  def transformValues(f: V => V): USTM[Unit] =
    transform((k, v) => k -> f(v))

  /**
   * Atomically updates all values using a transactional function.
   */
  def transformValuesSTM[R, E](f: V => ZSTM[R, E, V]): ZSTM[R, E, Unit] =
    transformSTM((k, v) => f(v).map(k -> _))

  /**
   * Updates the mapping for the specified key with the specified function,
   * which takes the current value of the key as an input, if it exists, and
   * either returns `Some` with a new value to indicate to update the value in
   * the map or `None` to remove the value from the map. Returns `Some` with the
   * updated value or `None` if the value was removed from the map.
   */
  def updateWith(k: K)(f: Option[V] => Option[V]): USTM[Option[V]] =
    get(k).flatMap(f(_).fold[USTM[Option[V]]](delete(k).as(None))(v => put(k, v).as(Some(v))))

  /**
   * Updates the mapping for the specified key with the specified transactional
   * function, which takes the current value of the key as an input, if it
   * exists, and either returns `Some` with a new value to indicate to update
   * the value in the map or `None` to remove the value from the map. Returns
   * `Some` with the updated value or `None` if the value was removed from the
   * map.
   */
  def updateWithSTM[R, E](k: K)(f: Option[V] => ZSTM[R, E, Option[V]]): ZSTM[R, E, Option[V]] =
    get(k).flatMap(f).flatMap {
      case None    => delete(k).as(None)
      case Some(v) => put(k, v).as(Some(v))
    }

  /**
   * Collects all values stored in map.
   */
  def values: USTM[List[V]] =
    toList.map(_.map(_._2))
}

object TMap {

  /**
   * Makes an empty `TMap`.
   */
  def empty[K, V]: USTM[TMap[K, V]] =
    fromIterable(Nil)

  /**
   * Makes a new `TMap` initialized with provided iterable.
   */
  def fromIterable[K, V](data: => Iterable[(K, V)]): USTM[TMap[K, V]] =
    make(data.toSeq: _*)

  /**
   * Makes a new `TMap` that is initialized with specified values.
   */
  def make[K, V](data: (K, V)*): USTM[TMap[K, V]] =
    ZSTM.succeed(unsafe.make(data: _*)(Unsafe.unsafe))

  object unsafe {
    def make[K, V](data: (K, V)*)(implicit unsafe: Unsafe): TMap[K, V] = {
      val size     = data.size
      val capacity = if (size < InitialCapacity) InitialCapacity else nextPowerOfTwo(size)

      val buckets  = Array.fill[List[(K, V)]](capacity)(Nil)
      val distinct = data.toMap

      val it = distinct.iterator
      while (it.hasNext) {
        val kv  = it.next()
        val idx = indexOf(kv._1, capacity)

        buckets(idx) = kv :: buckets(idx)
      }

      val tArray   = TArray.unsafe.make(buckets: _*)
      val tBuckets = TRef.unsafe.make(tArray)
      val tSize    = TRef.unsafe.make(size)
      new TMap(tBuckets, tSize)
    }
  }

  private def hash[K](k: K): Int = {
    val h = k.hashCode()
    h ^ (h >>> 16)
  }

  private def indexOf[K](k: K, capacity: Int): Int = hash(k) & (capacity - 1)

  private def nextPowerOfTwo(size: Int): Int = {
    val n = -1 >>> Integer.numberOfLeadingZeros(size - 1)
    if (n < 0) 1 else n + 1
  }

  private final val InitialCapacity = 16
  private final val LoadFactor      = 0.75
}
