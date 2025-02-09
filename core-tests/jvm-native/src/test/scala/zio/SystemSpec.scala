package zio

import zio.test.Assertion._
import zio.test._

import java.io.File

object SystemSpec extends ZIOBaseSpec {

  def spec: Spec[Any, Any] = suite("SystemSpec")(
    suite("Fetch an environment variable and check that")(
      test("If it exists, return a reasonable value") {
        assertZIO(live(System.env("PATH")))(isSome(containsString(File.separator + "bin")))
      },
      test("If it does not exist, return None") {
        assertZIO(live(System.env("QWERTY")))(isNone)
      }
    ),
    suite("Fetch all environment variables and check that")(
      test("If it exists, return a reasonable value") {
        assertZIO(live(System.envs.map(_.get("PATH"))))(isSome(containsString(File.separator + "bin")))
      } @@ TestAspect.unix,
      test("If it does not exist, return None") {
        assertZIO(live(System.envs.map(_.get("QWERTY123"))))(isNone)
      }
    ),
    suite("Fetch all VM properties and check that")(
      test("If it exists, return a reasonable value") {
        val expected = if (TestPlatform.isNative) "Scala Native" else "VM"
        assertZIO(live(System.properties.map(_.get("java.vm.name"))))(isSome(containsString(expected)))
      },
      test("If it does not exist, return None") {
        assertZIO(live(System.properties.map(_.get("qwerty"))))(isNone)
      }
    ),
    suite("Fetch a VM property and check that")(
      test("If it exists, return a reasonable value") {
        val expected = if (TestPlatform.isNative) "Scala Native" else "VM"
        assertZIO(live(System.property("java.vm.name")))(isSome(containsString(expected)))
      },
      test("If it does not exist, return None") {
        assertZIO(live(System.property("qwerty")))(isNone)
      }
    ),
    suite("Fetch the system's line separator and check that")(
      test("it is identical to System.lineSeparator") {
        assertZIO(live(System.lineSeparator))(equalTo(java.lang.System.lineSeparator))
      }
    )
  )
}
