package zio.test.magnolia

import magnolia1._
import zio.test.diff.{Diff, DiffResult}

object DeriveDiff extends DeriveDiff

trait DeriveDiff extends LowPri {
  type Typeclass[A] = Diff[A]

  def join[A](caseClass: CaseClass[Diff, A]): Diff[A] =
    (x: A, y: A) => {
      val fields = caseClass.parameters.map { param =>
        Some(param.label) -> param.typeclass.diff(param.dereference(x), param.dereference(y))
      }

      DiffResult.Nested(caseClass.typeName.short, fields.toList)
    }

  def split[A](ctx: SealedTrait[Diff, A]): Diff[A] = (x: A, y: A) =>
    ctx.split(x) {
      case sub if sub.cast.isDefinedAt(y) => sub.typeclass.diff(sub.cast(x), sub.cast(y))
      case _                              => DiffResult.Different(x, y)
    }

  implicit def gen[A]: Typeclass[A] = macro Magnolia.gen[A]
}

trait LowPri {
  def fallback[A]: Diff[A] = new Diff[A] {
    override def diff(x: A, y: A): DiffResult =
      if (x == y) DiffResult.Identical(x)
      else DiffResult.Different(x, y)

    override def isLowPriority: Boolean = true
  }
}
