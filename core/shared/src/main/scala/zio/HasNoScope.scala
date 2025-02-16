package zio

@scala.annotation.implicitNotFound(
  """Can not proof that ${R} does not contain Scope.
If ${R} contains a zio.Scope, please handle it explicitly. If it contains a generic type, add a context bound
like def myMethod[R: HasNoScope](...) = ..."""
)
sealed trait HasNoScope[R]

object HasNoScope extends HasNoScopeCompanionVersionSpecific {
  val noScope: HasNoScope[Any] = new HasNoScope[Any] {}
}
