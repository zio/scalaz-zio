package zio

import zio.test._

import scala.annotation.tailrec

trait ZIOBaseSpec extends DefaultRunnableSpec {
  override def aspects: List[TestAspectAtLeastR[Has[Live]]] =
    if (TestPlatform.isJVM) List(TestAspect.timeout(120.seconds))
    else List(TestAspect.sequential, TestAspect.timeout(120.seconds))

  override def runner: TestRunner[Environment, Any] =
    defaultTestRunner.withRuntimeConfig(self =>
      self.copy(runtimeConfigFlags = self.runtimeConfigFlags + RuntimeConfigFlag.EnableCurrentFiber)
    )

  sealed trait ZIOTag {
    val value: String
    val subTags: List[ZIOTag] = Nil
  }
  object ZIOTag {
    case object errors extends ZIOTag { override val value = "errors" }
    case object future extends ZIOTag { override val value = "future" }
    case object interop extends ZIOTag {
      override val value                 = "interop"
      override val subTags: List[ZIOTag] = List(future)
    }
    case object interruption extends ZIOTag { override val value = "interruption" }
    case object regression   extends ZIOTag { override val value = "regression"   }
    case object supervision  extends ZIOTag { override val value = "supervision"  }
  }

  def zioTag(zioTag: ZIOTag, zioTags: ZIOTag*): TestAspectPoly = {
    val tags = zioTags.map(_.value) ++ getSubTags(zioTag) ++ zioTags.flatMap(getSubTags)
    TestAspect.tag(zioTag.value, tags.distinct: _*)
  }

  private def getSubTags(zioTag: ZIOTag): List[String] = {
    @tailrec
    def loop(currentZioTag: ZIOTag, remainingZioTags: List[ZIOTag], result: List[String]): List[String] =
      (currentZioTag.subTags, remainingZioTags) match {
        case (Nil, Nil)      => currentZioTag.value :: result
        case (Nil, t :: ts)  => loop(t, ts, currentZioTag.value :: result)
        case (st :: sts, ts) => loop(st, sts ++ ts, currentZioTag.value :: result)
      }
    zioTag.subTags match {
      case t :: ts => loop(t, ts, Nil)
      case Nil     => Nil
    }
  }
}
