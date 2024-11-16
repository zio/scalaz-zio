package zio.internal.stacktracer

import scala.reflect.macros.{blackbox, whitebox}
import zio.internal.stacktracer.Tracer.createTrace
import zio.stacktracer.DisableAutoTrace

object Macros {

  def sourceLocation(c: blackbox.Context): c.Tree = {
    import c.universe._
    val pos = c.macroApplication.pos
    q"""
      _root_.zio.internal.stacktracer.SourceLocation(
        path = ${pos.source.file.path},
        line = ${pos.line}
      )
    """
  }

  def traceInfo(c: blackbox.Context): String = {

    val location = {
      def loop(current: c.Symbol, acc: List[String] = Nil): List[String] = {
        val currentName = current.name.decodedName.toString.trim
        if (currentName != "<root>")
          loop(current.owner, if (currentName == "$anonfun") acc else currentName :: acc)
        else acc
      }

      loop(c.internal.enclosingOwner).mkString(".")
    }

    val pos  = c.enclosingPosition
    val file = pos.source.file.name
    val line = pos.line
    createTrace(location, file, line)
  }

  def newTraceImpl(c: blackbox.Context): c.Expr[Tracer.instance.Type with zio.internal.stacktracer.Tracer.Traced] =
    traceExpr(traceInfo(c), c)

  def autoTraceImpl(c: whitebox.Context): c.Expr[Tracer.instance.Type with zio.internal.stacktracer.Tracer.Traced] = {
    import c.universe._

    val disableAutoTrace =
      c.inferImplicitValue(weakTypeOf[DisableAutoTrace]) != EmptyTree

    val implicitTraceInScope =
      c.inferImplicitValue(weakTypeOf[Tracer.instance.Type], withMacrosDisabled = true) != EmptyTree

    val traceExpression = traceExpr(traceInfo(c), c)

    if (!disableAutoTrace) {
      if (implicitTraceInScope)
        c.abort(c.enclosingPosition, "we already have an implicit trace - pass it through")
      else //there's no implicit, so generate one
        traceExpression
    } else {
      println(
        s"""[${Console.RED}error${Console.RESET}] ${traceInfo(c)}
           |[${Console.RED}error${Console.RESET}]  
           |[${Console.RED}error${Console.RESET}]  No automatically generated traces are permitted here. Add an implicit parameter
           |[${Console.RED}error${Console.RESET}]  to pass through a user generated trace or explicitly call `newTrace`
           |[${Console.RED}error${Console.RESET}]  to force generation of a new trace.
           |[${Console.RED}error${Console.RESET}]  
           |[${Console.RED}error${Console.RESET}]  copy/paste:
           |[${Console.RED}error${Console.RESET}]    (implicit trace: Trace)  <- no existing implicit parameter list
           |[${Console.RED}error${Console.RESET}]    , trace: Trace           <- existing implicit parameter list
           |[${Console.RED}error${Console.RESET}]    (newTrace)                       <- I know what I'm doing, generate a new trace anyway
           |[${Console.RED}error${Console.RESET}]    
           |""".stripMargin
      )
      c.abort(c.enclosingPosition, "Auto-generated traces are disabled")
    }

  }

  private def traceExpr(trace: String, c: blackbox.Context): c.Expr[Tracer.instance.Type with Tracer.Traced] = {
    import c.universe._
    c.Expr[Tracer.instance.Type with Tracer.Traced](
      q"""$trace.asInstanceOf[_root_.zio.internal.stacktracer.Tracer.instance.Type with _root_.zio.internal.stacktracer.Tracer.Traced]"""
    )
  }
}
