package zio

@scala.annotation.implicitNotFound(
  """Can not prove that ${R} does not contain Scope.
If ${R} contains a zio.Scope, please handle it explicitly. If it contains a generic type, add a context bound
like def myMethod[R: HasNoScope](...) = ..."""
)
sealed trait HasNoScope[R]

object HasNoScope extends HasNoScopeCompanionVersionSpecific {
  override val instance: HasNoScope[Any] = new HasNoScope[Any] {}
}
