package zio.test

import zio._
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.immutable.SortedSet

/**
 * The `Annotations` trait provides access to an annotation map that tests can
 * add arbitrary annotations to. Each annotation consists of a string
 * identifier, an initial value, and a function for combining two values.
 * Annotations form monoids and you can think of `Annotations` as a more
 * structured logging service or as a super polymorphic version of the writer
 * monad effect.
 */
trait Annotations extends Serializable {
  def annotate[V](key: TestAnnotation[V], value: V)(implicit trace: Trace): UIO[Unit]
  def get[V](key: TestAnnotation[V])(implicit trace: Trace): UIO[V]
  def withAnnotation[R, E](zio: ZIO[R, TestFailure[E], TestSuccess])(implicit
    trace: Trace
  ): ZIO[R, TestFailure[E], TestSuccess]
  def supervisedFibers(implicit trace: Trace): UIO[SortedSet[Fiber.Runtime[Any, Any]]]
  private[zio] def unsafe: UnsafeAPI
  private[zio] trait UnsafeAPI {
    def annotate[V](key: TestAnnotation[V], value: V)(implicit unsafe: Unsafe): Unit
  }
}

object Annotations {

  val tag: Tag[Annotations] = Tag[Annotations]

  final case class Test(ref: Ref.Atomic[TestAnnotationMap]) extends Annotations {
    def annotate[V](key: TestAnnotation[V], value: V)(implicit trace: Trace): UIO[Unit] =
      ref.update(_.annotate(key, value))
    def get[V](key: TestAnnotation[V])(implicit trace: Trace): UIO[V] =
      ref.get.map(_.get(key))
    def withAnnotation[R, E](zio: ZIO[R, TestFailure[E], TestSuccess])(implicit
      trace: Trace
    ): ZIO[R, TestFailure[E], TestSuccess] =
      zio.foldZIO(e => ref.get.map(e.annotated).flip, a => ref.get.map(a.annotated))
    def supervisedFibers(implicit trace: Trace): UIO[SortedSet[Fiber.Runtime[Any, Any]]] =
      ZIO.fiberIdWith { fiberId =>
        get(TestAnnotation.fibers).flatMap {
          case Left(_) => ZIO.succeed(SortedSet.empty[Fiber.Runtime[Any, Any]])
          case Right(refs) =>
            ZIO
              .foreach(refs)(ref => ZIO.succeed(ref.get))
              .map(_.foldLeft(SortedSet.empty[Fiber.Runtime[Any, Any]])(_ ++ _))
              .map(_.filter(_.id != fiberId))
        }
      }
    private[zio] def unsafe: UnsafeAPI =
      new UnsafeAPI {
        def annotate[V](key: TestAnnotation[V], value: V)(implicit unsafe: Unsafe): Unit =
          ref.unsafe.update(_.annotate(key, value))
      }
  }

  /**
   * Accesses an `Annotations` instance in the environment and appends the
   * specified annotation to the annotation map.
   */
  def annotate[V](key: TestAnnotation[V], value: V)(implicit trace: Trace): UIO[Unit] =
    annotationsWith(_.annotate(key, value))

  /**
   * Accesses an `Annotations` instance in the environment and retrieves the
   * annotation of the specified type, or its default value if there is none.
   */
  def get[V](key: TestAnnotation[V])(implicit trace: Trace): UIO[V] =
    annotationsWith(_.get(key))

  /**
   * Returns a set of all fibers in this test.
   */
  def supervisedFibers(implicit trace: Trace): UIO[SortedSet[Fiber.Runtime[Any, Any]]] =
    annotationsWith(_.supervisedFibers)

  /**
   * Constructs a new `Annotations` service.
   */
  val live: ULayer[Annotations] = {
    implicit val trace = Tracer.newTrace
    ZLayer.scoped {
      for {
        ref        <- ZIO.succeed(Unsafe.unsafe(Ref.unsafe.make(TestAnnotationMap.empty)(_)))
        annotations = Test(ref)
        _          <- withAnnotationsScoped(annotations)
      } yield annotations
    }
  }

  /**
   * Accesses an `Annotations` instance in the environment and executes the
   * specified effect with an empty annotation map, returning the annotation map
   * along with the result of execution.
   */
  def withAnnotation[R, E](zio: ZIO[R, TestFailure[E], TestSuccess])(implicit
    trace: Trace
  ): ZIO[R, TestFailure[E], TestSuccess] =
    annotationsWith(_.withAnnotation(zio))
}
