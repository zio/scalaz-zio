package zio.test.junit.maven

import zio.test.Assertion.equalTo
import zio.test.{Spec, TestEnvironment, assert, ZIOSpecDefault}
import zio.{Scope, Task, ZIO, ZLayer}

trait Ops {
 def targetHost: String
}

object OpsTest extends Ops {
  override def targetHost: String = null
}

trait MyService {
  def readData : Task[List[String]]
}

class MyServiceTest(targetHostName: String) extends MyService {

  val url = s"https://${targetHostName.toLowerCase}/ws" // <- null pointer exception here

  override def readData: Task[List[String]] = {
    ZIO.succeed(List("a","b"))
  }
}

object DefectSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment with Scope, Any] = suite("nul test")(
    test("test with defect") {
      for {
        ms <- ZIO.service[MyService]
        result <- ms.readData
      }
      yield assert(result.size)(equalTo(2))
    }.provideLayer(ZLayer.succeed(new MyServiceTest(OpsTest.targetHost)))
  )
}
