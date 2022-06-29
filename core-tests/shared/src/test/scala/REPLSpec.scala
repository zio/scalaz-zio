import com.github.ghik.silencer.silent
import zio.test._

object REPLSpec extends ZIOSpecDefault {

  @silent("Unused import")
  def spec = suite("REPLSpec")(
    test("settings compile") {
      import zio.Runtime.default._
      import zio._
      import zio.Console._
      @silent("never used")
      implicit class RunSyntax[A](io: ZIO[Any, Any, A]) {
        def unsafeRun: A =
          Unsafe.unsafe { implicit unsafe =>
            Runtime.default.unsafe.run(io).getOrThrowFiberFailure()
          }
      }
      assertCompletes
    }
  )
}
