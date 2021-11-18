package zio.test

import zio.{UIO, URIO}

import scala.annotation.tailrec

package object sbt {

  type SendSummary = URIO[Summary, Unit]

  object SendSummary {
    def fromSend(send: Summary => Unit): SendSummary =
      URIO.serviceWithZIO(summary => URIO.succeed(send(summary)))

    def fromSendM(send: Summary => UIO[Unit]): SendSummary =
      URIO.serviceWithZIO(send)

    def noop: SendSummary =
      UIO.unit
  }

  /**
   * Inserts the ANSI escape code for the current color at the beginning of each
   * line of the specified string so the string will be displayed with the
   * correct color by the `SBTTestLogger`.
   */
  private[sbt] def colored(s: String): String = {
    @tailrec
    def loop(s: String, i: Int, color: Option[String]): String =
      if (i >= s.length) s
      else {
        val s1 = s.slice(i, i + 5)
        val isColor = s1 == Console.BLUE ||
          s1 == Console.CYAN ||
          s1 == Console.GREEN ||
          s1 == Console.RED ||
          s1 == Console.YELLOW
        if (isColor)
          loop(s, i + 5, Some(s1))
        else if (s.slice(i, i + 4) == Console.RESET)
          loop(s, i + 4, None)
        else if (s.slice(i, i + 1) == "\n" && color.isDefined)
          loop(s.patch(i + 1, color.get, 0), i + 6, color)
        else loop(s, i + 1, color)

      }
    loop(s, 0, None)
  }
}
