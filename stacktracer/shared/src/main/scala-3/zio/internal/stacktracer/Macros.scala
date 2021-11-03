package zio.internal.stacktracer

import com.github.ghik.silencer.silent
import zio.internal.stacktracer.Tracer.createTrace
import zio.stacktracer.DisableAutoTrace

import scala.quoted._

@silent
object Macros {

  def traceInfo(using ctx: Quotes): String = {
    import quotes.reflect._

    val location = {
      def loop(current: Symbol, acc: List[String] = Nil): List[String] = {
        val currentName = current.name.toString.trim
        if (currentName != "<root>")
          loop(current.owner, if (currentName == "$anonfun") acc else currentName :: acc)
        else acc
      }

      loop(Symbol.spliceOwner).mkString(".")
    }

    val pos    = Position.ofMacroExpansion
    val file   = pos.sourceFile.path.toString
    val line   = pos.startLine + 1
    val column = pos.startColumn
    createTrace(location, file, line, column)
  }

  def newTraceImpl(using ctx: Quotes): Expr[Tracer.instance.Type] =
    traceExpr(traceInfo)

  def autoTraceImpl(using ctx: Quotes): Expr[Tracer.instance.Type] = {
    import quotes.reflect._

    val disableAutoTrace =
      Expr.summon[DisableAutoTrace].isDefined

    val traceExpression = traceExpr(traceInfo)

    if (!disableAutoTrace) traceExpression
    else {
      println(
        s"""[${Console.RED}error${Console.RESET}] ${traceInfo}
           |[${Console.RED}error${Console.RESET}]  
           |[${Console.RED}error${Console.RESET}]  No automatically generated traces are permitted here. Add an implicit parameter
           |[${Console.RED}error${Console.RESET}]  to pass through a user generated trace or explicitly call `newTrace`
           |[${Console.RED}error${Console.RESET}]  to force generation of a new trace.
           |[${Console.RED}error${Console.RESET}]  
           |[${Console.RED}error${Console.RESET}]  copy/paste:
           |[${Console.RED}error${Console.RESET}]    (implicit trace: ZTraceElement)  <- no existing implicit parameter list
           |[${Console.RED}error${Console.RESET}]    , trace: ZTraceElement           <- existing implicit parameter list
           |[${Console.RED}error${Console.RESET}]    (newTrace)                       <- I know what I'm doing, generate a new trace anyway
           |[${Console.RED}error${Console.RESET}]    
           |""".stripMargin
      )
      report.throwError("Auto-generated traces are disabled")
    }

  }

  private def traceExpr(trace: String)(using ctx: Quotes): Expr[Tracer.instance.Type] =
    Expr(trace).asInstanceOf[Expr[Tracer.instance.Type]]
}
