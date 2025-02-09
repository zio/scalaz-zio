package zio.internal.stacktracer

private[internal] object TracerUtils {

  /**
   * Parse the trace string into location, file and line
   *
   * Implementation note: It parses the string from the end to the beginning for
   * performances reasons. See zio.internal.stacktracer.Tracer.createTrace for
   * the format of the trace
   *
   * @param trace
   *   the trace string
   * @return
   *   successfully parsed trace or null if the trace is invalid
   */
  def parse(trace: String): ParsedTrace = {
    // Checking the closing parentesis starting from the end of the trace
    val closingParentesisIdx = trace.length - 1
    if (closingParentesisIdx < 0 || trace.charAt(closingParentesisIdx) != ')') null
    else {
      // Parsing the line number down to the colon
      var colonIdx    = closingParentesisIdx - 1
      var digitWeight = 1
      var line        = 0
      var ch: Char    = 0
      while (
        colonIdx >= 0 && {
          ch = trace.charAt(colonIdx)
          ch != ':'
        }
      ) {
        if (
          ch < '0' || ch > '9' || (digitWeight == 1000000000 && ch > '2') || {
            line += (ch - '0') * digitWeight
            digitWeight *= 10
            line < 0
          }
        ) return null
        colonIdx -= 1
      }
      // Finding the opening parentesis
      var openingParentesisIdx = colonIdx - 1
      while (openingParentesisIdx >= 0 && trace.charAt(openingParentesisIdx) != '(') {
        openingParentesisIdx -= 1
      }
      if (line == 0 || openingParentesisIdx < 0) null
      else
        ParsedTrace(
          location = trace.substring(0, openingParentesisIdx),
          file = trace.substring(openingParentesisIdx + 1, colonIdx),
          line = line
        )
    }
  }
}
