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
    val empty: Type with Traced = "".intern().asInstanceOf[Type with Traced]
    def unapply(trace: Type): Option[(String, String, Int)] =
      trace match {
        case regex(location, file, line) => Some((location, file, line.toInt))
        case _                           => None
      }

    def apply(location: String, file: String, line: Int): Type with Traced =
      createTrace(location, file, line).asInstanceOf[Type with Traced]
  }

  private[internal] def createTrace(location: String, file: String, line: Int): String =
    s"$location($file:$line)".intern

  private val regex = """(.*?)\((.*?):([^:]*?)\)""".r
}

sealed trait Tracer {
  type Type <: AnyRef
  val empty: Type with Tracer.Traced
  def unapply(trace: Type): Option[(String, String, Int)]
  def apply(location: String, file: String, line: Int): Type with Tracer.Traced
}
