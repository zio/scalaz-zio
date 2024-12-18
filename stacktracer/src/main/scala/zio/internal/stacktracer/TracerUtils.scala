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
    // Checking the closing parentesis
    val closingParentesisIdx = trace.length - 1
    if (closingParentesisIdx < 0 || trace.charAt(closingParentesisIdx) != ')') null
    else {
      // Finding the colon
      var colonIdx = closingParentesisIdx - 1
      while (colonIdx >= 0 && trace.charAt(colonIdx) != ':') {
        colonIdx -= 1
      }
      if (colonIdx < 0) null
      else {
        // Finding the opening parentesis
        var openingParentesisIdx = colonIdx - 1
        while (openingParentesisIdx >= 0 && trace.charAt(openingParentesisIdx) != '(') {
          openingParentesisIdx -= 1
        }
        if (openingParentesisIdx < 0) null
        else {
          // Parsing the line number
          var line     = 0
          var digitIdx = colonIdx + 1
          while (digitIdx < closingParentesisIdx) {
            val ch = trace.charAt(digitIdx)
            digitIdx += 1
            if (
              ch < '0' || ch > '9' || line > 214748364 || {
                line = line * 10 + (ch - '0')
                line <= 0
              }
            ) return null
          }
          ParsedTrace(
            location = trace.substring(0, openingParentesisIdx),
            file = trace.substring(openingParentesisIdx + 1, colonIdx),
            line = line
          )
        }
      }
    }
  }
}
