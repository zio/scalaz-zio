package zio.test.scalacheck

import org.scalacheck.{Prop, Properties}
import zio.Scope
import zio.test._

object AssertionSpec extends ZIOSpecDefault {
  object FailingProperties extends Properties("MyProperties") {
    property("PassingProp") = Prop.propBoolean(false)
  }

  object PassingProperties extends Properties("MyProperties") {
    property("FailingProp") = Prop.propBoolean(true)
  }

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("ZIO assertions for ScalaCheck")(
      test("Prop passing")(Prop.propBoolean(true).assertZIO()),
      test("Prop failing")(Prop.propBoolean(false).assertZIO()) @@ TestAspect.failing,
      test("Properties passing")(PassingProperties.assertZIO()),
      test("Properties failing")(FailingProperties.assertZIO()) @@ TestAspect.failing
    )
}
