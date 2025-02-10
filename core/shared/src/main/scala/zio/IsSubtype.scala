package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.annotation.implicitNotFound

@implicitNotFound("\nThis operator requires that the output type be a subtype of ${B}\nBut the actual type was ${A}.")
sealed abstract class IsSubtypeOfOutput[-A, +B] extends (A => B) with Serializable
object IsSubtypeOfOutput {
  private val instance: IsSubtypeOfOutput[Any, Any] = new IsSubtypeOfOutput[Any, Any] { def apply(a: Any): Any = a }

  implicit def impl[A, B](implicit subtype: A <:< B): IsSubtypeOfOutput[A, B] =
    instance.asInstanceOf[IsSubtypeOfOutput[A, B]]

  implicit def implNothing[B]: IsSubtypeOfOutput[Nothing, B] =
    instance.asInstanceOf[IsSubtypeOfOutput[Nothing, B]]
}

@implicitNotFound("\nThis operator requires that the error type be a subtype of ${B}\nBut the actual type was ${A}.")
sealed abstract class IsSubtypeOfError[-A, +B] extends (A => B) with Serializable
object IsSubtypeOfError {
  private val instance: IsSubtypeOfError[Any, Any] = new IsSubtypeOfError[Any, Any] { def apply(a: Any): Any = a }

  implicit def impl[A, B](implicit subtype: A <:< B): IsSubtypeOfError[A, B] =
    instance.asInstanceOf[IsSubtypeOfError[A, B]]

  implicit def implNothing[B]: IsSubtypeOfError[Nothing, B] =
    instance.asInstanceOf[IsSubtypeOfError[Nothing, B]]
}
