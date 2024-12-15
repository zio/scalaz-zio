package zio.internal.stacktracer

object TracerUtils {

  /**
   * Parse the trace string into location, file and line
   *
   * Implementation note: It parses the string from the end to the beginning for
   * performances reasons. See [[zio.internal.stacktracer.Tracer.createTrace]]
   * for the format of the trace
   */
  def parse(trace: String): Option[(String, String, Int)] = {
    val length = trace.length

    if (length == 0 || trace.charAt(length - 1) != ')') return None

    var idx = length - 2 // start from the end - 2 because the last character is ')'

    var openingParentesisIdx = -1
    var colonIdx             = -1

    // Finding the colon
    while (idx > 0) {
      val c = trace.charAt(idx)
      if (c == ':') {
        colonIdx = idx
        idx = 0 // stop loop
      } else idx -= 1
    }

    if (colonIdx == -1) return None
    else idx = colonIdx - 1

    // Finding the opening parentesis
    while (idx >= 0) {
      val c = trace.charAt(idx)
      if (c == '(') {
        openingParentesisIdx = idx
        idx = -1 // stop loop
      } else idx -= 1
    }

    if (openingParentesisIdx == -1) None
    else {
      val location = trace.substring(0, openingParentesisIdx)
      val file     = trace.substring(openingParentesisIdx + 1, colonIdx)
      val line     = trace.substring(colonIdx + 1, length - 1)
      Some((location, file, line.toInt))
    }
  }

}
