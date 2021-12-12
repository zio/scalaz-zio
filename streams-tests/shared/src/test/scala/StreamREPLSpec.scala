import com.github.ghik.silencer.silent
import zio.test._

object StreamREPLSpec extends ZIOSpecDefault {

  @silent("Unused import")
  def spec = suite("StreamREPLSpec")(
    test("settings compile") {
      import zio.Runtime.default._
      import zio._
      import zio.Console._
      import zio.stream._
      @silent("never used")
      implicit class RunSyntax[A](io: ZIO[ZEnv, Any, A]) {
        def unsafeRun: A =
          Runtime.default.unsafeRun(io.provideLayer(ZEnv.live))
      }
      assertCompletes
    }
  )
}
