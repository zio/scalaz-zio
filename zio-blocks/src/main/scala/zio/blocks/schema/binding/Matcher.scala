package zio.blocks.schema.binding

/**
 * A {{Matcher}} is a typeclass that can match a value of type `A` with a
 * specific term of its sum type.
 */
trait Matcher[+A] {

  /**
   * An unsafe unsafeDowncast operation that attempts to unsafeDowncast a value
   * of type `Any` to a value of type `A`. If the unsafeDowncast fails, an
   * `IllegalArgumentException` is thrown.
   */
  def unsafeDowncast(any: Any): A

  /**
   * A safe unsafeDowncast operation that attempts to unsafeDowncast a value of
   * type `Any` to a value of type `A`. If the unsafeDowncast fails, `None` is
   * returned.
   */
  final def downcastOption(any: Any): Option[A] =
    try {
      Some(unsafeDowncast(any))
    } catch {
      case _: IllegalArgumentException => None
    }
}
object Matcher {
  def apply[A](implicit m: Matcher[A]): Matcher[A] = m

  implicit def some[A]: Matcher[Some[A]] = _someMatcher.asInstanceOf[Matcher[Some[A]]]

  implicit val none: Matcher[None.type] = _noneMatcher

  implicit def left[A, B]: Matcher[Left[A, B]] = _leftMatcher.asInstanceOf[Matcher[Left[A, B]]]

  implicit def right[A, B]: Matcher[Right[A, B]] = _rightMatcher.asInstanceOf[Matcher[Right[A, B]]]

  implicit def success[A]: Matcher[scala.util.Success[A]] = _successMatcher.asInstanceOf[Matcher[scala.util.Success[A]]]

  implicit def failure[A]: Matcher[scala.util.Failure[A]] = _failureMatcher.asInstanceOf[Matcher[scala.util.Failure[A]]]

  private val _someMatcher: Matcher[Some[Any]] = new Matcher[Some[Any]] {
    def unsafeDowncast(any: Any): Some[Any] = any match {
      case x @ Some(_) => x.asInstanceOf[Some[Any]]
      case _           => throw new IllegalArgumentException(s"Expected Some, got $any")
    }
  }

  private val _noneMatcher: Matcher[None.type] = new Matcher[None.type] {
    def unsafeDowncast(any: Any): None.type = any match {
      case x @ None => None
      case _        => throw new IllegalArgumentException(s"Expected None, got $any")
    }
  }

  private val _leftMatcher: Matcher[Left[Any, Any]] = new Matcher[Left[Any, Any]] {
    def unsafeDowncast(any: Any): Left[Any, Any] = any match {
      case x @ Left(_) => x.asInstanceOf[Left[Any, Any]]
      case _           => throw new IllegalArgumentException(s"Expected Left, got $any")
    }
  }

  private val _rightMatcher: Matcher[Right[Any, Any]] = new Matcher[Right[Any, Any]] {
    def unsafeDowncast(any: Any): Right[Any, Any] = any match {
      case x @ Right(_) => x.asInstanceOf[Right[Any, Any]]
      case _            => throw new IllegalArgumentException(s"Expected Right, got $any")
    }
  }

  private val _successMatcher: Matcher[scala.util.Success[Any]] = new Matcher[scala.util.Success[Any]] {
    def unsafeDowncast(any: Any): scala.util.Success[Any] = any match {
      case x @ scala.util.Success(_) => x.asInstanceOf[scala.util.Success[Any]]
      case _                         => throw new IllegalArgumentException(s"Expected Success, got $any")
    }
  }

  private val _failureMatcher: Matcher[scala.util.Failure[Any]] = new Matcher[scala.util.Failure[Any]] {
    def unsafeDowncast(any: Any): scala.util.Failure[Any] = any match {
      case x @ scala.util.Failure(_) => x.asInstanceOf[scala.util.Failure[Any]]
      case _                         => throw new IllegalArgumentException(s"Expected Failure, got $any")
    }
  }
}
