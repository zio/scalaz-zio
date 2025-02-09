package zio.test.magnolia

import magnolia1._
import zio.test.diff.{Diff, DiffInstances, DiffResult}

import scala.quoted._

object DeriveDiff extends AutoDerivation[Diff] with LowPri {

  def join[A](caseClass: CaseClass[Diff, A]): Diff[A] =
    (x: A, y: A) => {
      val fields = caseClass.params.map { param =>
        Some(param.label) -> param.typeclass.diff(param.deref(x), param.deref(y))
      }

      DiffResult.Nested(caseClass.typeInfo.short, fields.toList)
    }

  def split[A](ctx: SealedTrait[Diff, A]): Diff[A] = (x: A, y: A) =>
    ctx.choose(x) {
      case sub if sub.cast.isDefinedAt(y) => sub.typeclass.diff(sub.cast(x), sub.cast(y))
      case _                              => DiffResult.Different(x, y)
    }

  implicit inline def gen[A: deriving.Mirror.Of]: Diff[A] =
    autoDerived[A]
}

trait LowPri {
  given fallback[A]: Diff[A] = new Diff[A] {
    override def diff(x: A, y: A): DiffResult =
      if (x == y) DiffResult.Identical(x)
      else DiffResult.Different(x, y)

    override def isLowPriority: Boolean = true
  }
}
