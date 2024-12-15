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

    /**
     * Parse the trace string into location, file and line
     *
     * Implementation note: It parses the string from the end to the beginning
     * for performances reasons.
     */
    def unapply(trace: Type): Option[(String, String, Int)] = {
      val length = trace.length

      if (length == 0 || trace.charAt(length - 1) != ')') return None

      var openingParentesisNotMet = true
      var colonNotMet             = true

      var idx = length - 2 // start from the end - 2 because the last character is ')'

      var openingParentesisIdx = -1
      var colonIdx             = -1

      // Finding the colon
      while (idx > 0) {
        val c = trace.charAt(idx)
        if (c == ':') {
          colonIdx = idx
          colonNotMet = false
          idx = 0 // stop loop
        } else idx -= 1
      }

      if (colonNotMet) return None
      else idx = colonIdx - 1

      // Finding the opening parentesis
      while (idx >= 0) {
        val c = trace.charAt(idx)
        if (c == '(') {
          openingParentesisIdx = idx
          openingParentesisNotMet = false
          idx = -1 // stop loop
        } else idx -= 1
      }

      if (openingParentesisNotMet) None
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
