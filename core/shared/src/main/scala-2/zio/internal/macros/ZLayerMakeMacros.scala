package zio.internal.macros

import zio.internal.ansi.AnsiStringOps
import zio.ZLayer

import scala.reflect.macros.blackbox
import zio.ZIO
import zio.IsSubtypeOfOutput

final class ZLayerMakeMacros(val c: blackbox.Context) extends LayerMacroUtils {
  import c.universe._

  def makeImpl[
    E,
    R0: c.WeakTypeTag,
    R: c.WeakTypeTag
  ](layer: c.Expr[ZLayer[_, E, _]]*)(
    dummyKRemainder: c.Expr[DummyK[R0]],
    dummyK: c.Expr[DummyK[R]]
  ): c.Expr[ZLayer[R0, E, R]] = {
    val _ = (dummyK, dummyKRemainder)
    assertEnvIsNotNothing[R]()
    constructLayer[R0, R, E](layer, ProvideMethod.Provide)
  }

  def makeSomeImpl[
    E,
    R0: c.WeakTypeTag,
    R: c.WeakTypeTag
  ](layer: c.Expr[ZLayer[_, E, _]]*)(
    dummyKRemainder: c.Expr[DummyK[R0]],
    dummyK: c.Expr[DummyK[R]]
  ): c.Expr[ZLayer[R0, E, R]] = {
    val _ = (dummyK, dummyKRemainder)
    assertEnvIsNotNothing[R]()
    constructLayer[R0, R, E](layer, ProvideMethod.ProvideSome)
  }

  def runWithImpl[
    R: c.WeakTypeTag,
    E,
    A
  ](layers: c.Expr[ZLayer[_, E, _]]*)(ev: c.Expr[A IsSubtypeOfOutput Unit]): c.Expr[ZIO[Any, E, Unit]] = {
    val constructedLayer = constructLayer[Any, R, E](layers, ProvideMethod.Provide)
    val self             = c.Expr[ZLayer[R, E, Unit]](q"${c.prefix}")
    reify {
      ZIO.scoped[R](self.splice.build).provideLayer(constructedLayer.splice).unit
    }
  }

  /**
   * Ensures the macro has been annotated with the intended result type. The
   * macro will not behave correctly otherwise.
   */
  private def assertEnvIsNotNothing[R: c.WeakTypeTag](): Unit = {
    val outType     = weakTypeOf[R]
    val nothingType = weakTypeOf[Nothing]
    if (outType == nothingType) {
      val errorMessage =
        s"""
${"  ZLayer Wiring Error  ".red.bold.inverted}
        
You must provide a type to ${"make".cyan.bold} (e.g. ${"ZLayer.make".cyan.bold}${"[A with B]".cyan.bold.underlined}${"(A.live, B.live)".cyan.bold})

"""
      c.abort(c.enclosingPosition, errorMessage)
    }
  }

}
