package zio.internal

object ansi {
  trait AnsiCode {
    def code: String
  }

  val Reset = "\u001b[0m"

  sealed abstract class Color(val code: String) extends AnsiCode

  object Color {
    case object Blue    extends Color("\u001b[34m")
    case object Cyan    extends Color("\u001b[36m")
    case object Green   extends Color("\u001b[32m")
    case object Magenta extends Color("\u001b[35m")
    case object Red     extends Color("\u001b[31m")
    case object Yellow  extends Color("\u001b[33m")
  }

  sealed abstract class Style(val code: String) extends AnsiCode

  object Style {
    case object Bold       extends Style("\u001b[1m")
    case object Faint      extends Style("\u001b[2m")
    case object Underlined extends Style("\u001b[4m")
    case object Reversed   extends Style("\u001b[7m")
  }

  implicit class AnsiStringOps(private val self: String) extends AnyVal {
    // Colors
    def blue: String    = withAnsi(Color.Blue)
    def cyan: String    = withAnsi(Color.Cyan)
    def green: String   = withAnsi(Color.Green)
    def magenta: String = withAnsi(Color.Magenta)
    def red: String     = withAnsi(Color.Red)
    def yellow: String  = withAnsi(Color.Yellow)

    // Styles
    def bold: String       = withAnsi(Style.Bold)
    def faint: String      = withAnsi(Style.Faint)
    def underlined: String = withAnsi(Style.Underlined)
    def inverted: String   = withAnsi(Style.Reversed)

    def withAnsi(ansiCode: AnsiCode): String = ansiCode.code + self + Reset
  }
}
