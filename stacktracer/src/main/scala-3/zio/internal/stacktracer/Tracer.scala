package zio.internal.stacktracer

object Tracer {
  type Traced = Any

  /**
   * This implicit is always in scope and will generate a new Trace any time one
   * is implicitly required (or the method is explicitly called)
   *
   * This can be disabled by importing `import
   * zio.stacktracer.TracingImplicits.disableAutoTrace`
   */
  inline given autoTrace: Tracer.instance.Type =
    ${ Macros.autoTraceImpl }

  /**
   * Explicitly generate a new trace
   */
  inline def newTrace: Tracer.instance.Type =
    ${ Macros.newTraceImpl }

  val instance: Tracer = new Tracer {
    type Type = String
    val empty = "".intern()
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
  val empty: Type
  def unapply(trace: Type): Option[(String, String, Int)]
  def apply(location: String, file: String, line: Int): Type with Tracer.Traced
}
