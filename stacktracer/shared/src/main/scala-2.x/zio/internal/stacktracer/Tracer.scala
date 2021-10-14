package zio.internal.stacktracer

object Tracer {
  trait Traced extends Any

  implicit def autoTrace: instance.Type with zio.internal.stacktracer.Tracer.Traced = macro Macros.autoTraceImpl

  /**
   * Explicitly generate a new trace
   */
  def newTrace: Tracer.instance.Type with zio.internal.stacktracer.Tracer.Traced = macro Macros.newTraceImpl

  val instance: Tracer = new Tracer {
    type Type = String
    val empty: Type with Traced = "".asInstanceOf[Type with Traced]
    def unapply(trace: Type): Option[(String, String, Int, Int)] =
      trace match {
        case regex(location, file, line, column) => Some((location, file, line.toInt, column.toInt))
        case _                                   => None
      }
  }

  private[internal] def createTrace(location: String, file: String, line: Int, column: Int): String =
    s"$location($file:$line:$column)".intern

  private val regex = """(.*?)\((.*?):(.*?):(.*?)\)""".r
}

sealed trait Tracer {
  type Type <: AnyRef
  val empty: Type with Tracer.Traced
  def unapply(trace: Type): Option[(String, String, Int, Int)]
}
