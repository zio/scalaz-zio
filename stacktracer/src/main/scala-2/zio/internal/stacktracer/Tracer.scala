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
    def unapply(trace: Type): Option[(String, String, Int)] = {
      var openingParentesisNotMet = true
      var colonNotMet             = true

      var idx    = 0
      val length = trace.length

      var openingParentesisIdx = -1
      var colonIdx             = -1

      // Finding the first opening parentesis
      while (idx < length) {
        val c = trace.charAt(idx)
        if (c == '(') {
          openingParentesisIdx = idx
          openingParentesisNotMet = false
          idx = length // stop loop
        } else idx += 1
      }

      if (openingParentesisNotMet) return None
      else idx = openingParentesisIdx + 1

      // Finding the colon
      while (idx < length) {
        val c = trace.charAt(idx)
        if (c == ':') {
          colonIdx = idx
          colonNotMet = false
          idx = length // stop loop
        } else idx += 1
      }

      if (openingParentesisNotMet || colonNotMet) None
      else {
        val location = trace.substring(0, openingParentesisIdx)
        val file     = trace.substring(openingParentesisIdx + 1, colonIdx)
        val line     = trace.substring(colonIdx + 1, length - 1)
        Some((location, file, line.toInt))
      }
    }

    def apply(location: String, file: String, line: Int): Type with Traced =
      createTrace(location, file, line).asInstanceOf[Type with Traced]
  }

  private[internal] def createTrace(location: String, file: String, line: Int): String =
    s"$location($file:$line)".intern

}

sealed trait Tracer {
  type Type <: AnyRef
  val empty: Type with Tracer.Traced
  def unapply(trace: Type): Option[(String, String, Int)]
  def apply(location: String, file: String, line: Int): Type with Tracer.Traced
}
