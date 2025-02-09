package zio.internal.stacktracer

import zio.internal.stacktracer.Tracer.createTrace
import zio.stacktracer.DisableAutoTrace

import scala.annotation.nowarn
import scala.quoted._

@nowarn
object Macros {

  def sourceLocation(using ctx: Quotes): Expr[SourceLocation] = {
    import quotes.reflect._
    val pos  = Position.ofMacroExpansion
    val path = pos.sourceFile.path
    val line = pos.startLine + 1
    '{ SourceLocation(${ Expr(path) }, ${ Expr(line) }) }
  }

  def traceInfo(using ctx: Quotes): String = {
    import quotes.reflect._

    val location = {
      def loop(current: Symbol, acc: List[String] = Nil): List[String] = {
        val currentName = current.name.trim.stripSuffix("$")
        if (currentName != "<root>")
          loop(current.owner, if (currentName == "$anonfun" || currentName == "macro") acc else currentName :: acc)
        else acc
      }

      loop(Symbol.spliceOwner).mkString(".")
    }

    val pos  = Position.ofMacroExpansion
    val file = pos.sourceFile.name
    val line = pos.startLine + 1
    createTrace(location, file, line)
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
           |[${Console.RED}error${Console.RESET}]    (implicit trace: Trace)  <- no existing implicit parameter list
           |[${Console.RED}error${Console.RESET}]    , trace: Trace           <- existing implicit parameter list
           |[${Console.RED}error${Console.RESET}]    (newTrace)                       <- I know what I'm doing, generate a new trace anyway
           |[${Console.RED}error${Console.RESET}]    
           |""".stripMargin
      )
      report.errorAndAbort("Auto-generated traces are disabled")
    }

  }

  private def traceExpr(trace: String)(using ctx: Quotes): Expr[Tracer.instance.Type] =
    Expr(trace).asInstanceOf[Expr[Tracer.instance.Type]]
}
