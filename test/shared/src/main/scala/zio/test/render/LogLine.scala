/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test.render

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.render.LogLine.Fragment.Style

object LogLine {
  case class Message(lines: Vector[Line] = Vector.empty) {

    def +(fragment: Fragment) =
      this +++ Message(Vector(Line(Vector(fragment))))

    def +:(line: Line) = Message(line +: lines)
    def :+(fragment: Fragment): Message = Message(lines match {
      case line +: lines => (fragment +: line) +: lines
      case _             => Vector(fragment.toLine)
    })
    def :+(line: Line)       = Message(lines :+ line)
    def ++(message: Message) = Message(lines ++ message.lines)
    def +++(message: Message) =
      (lines.lastOption, message.lines.headOption) match {
        case (Some(last), Some(head)) =>
          Message(lines.dropRight(1) :+ (last ++ head)) ++ Message(message.lines.drop(1))
        case _ =>
          this ++ message
      }
    def drop(n: Int): Message            = Message(lines.drop(n))
    def map(f: Line => Line): Message    = Message(lines = lines.map(f))
    def withOffset(offset: Int): Message = Message(lines.map(_.withOffset(offset)))
    def intersperse(line: Line): Message = Message(
      lines.foldRight(List.empty[Line]) { case (ln, rest) =>
        ln :: (if (rest.isEmpty) Nil else line :: rest)
      }
    )
  }
  object Message {
    def apply(lines: Seq[Line]): Message = Message(lines.toVector)
    def apply(lineText: String): Message = Fragment(lineText).toLine.toMessage
    val empty: Message                   = Message()
  }
  case class Line(fragments: Vector[Fragment] = Vector.empty, offset: Int = 0) { self =>
    def +:(fragment: Fragment): Line       = Line(fragment +: fragments)
    def :+(fragment: Fragment): Line       = Line(fragments :+ fragment)
    def +(fragment: Fragment): Line        = Line(fragments :+ fragment)
    def prepend(message: Message): Message = Message(this +: message.lines)
    def +(line: Line): Message             = Message(Vector(this, line))
    def ++(line: Line): Line               = copy(fragments = fragments ++ line.fragments)
    def withOffset(shift: Int): Line       = copy(offset = offset + shift)
    def toMessage: Message                 = Message(Vector(this))

    def optimized: Line = copy(fragments = optimize(fragments))
    private def optimize(fragments: Vector[Fragment]) =
      fragments
        .foldRight(List.empty[Fragment]) { case (curr, rest) =>
          rest match {
            case next :: fs =>
              if (curr.style == next.style) curr.copy(text = curr.text + next.text) :: fs
              else curr :: next :: fs
            case Nil => curr :: Nil
          }
        }
        .toVector
  }
  object Line {
    def fromString(text: String, offset: Int = 0): Line = Fragment(text).toLine.withOffset(offset)
    val empty: Line                                     = Line()
  }
  case class Fragment(text: String, style: Style = Style.Default) { self =>
    def +:(line: Line)            = prepend(line)
    def prepend(line: Line): Line = Line(this +: line.fragments, line.offset)
    def +(f: Fragment)            = Line(Vector(this, f))
    def toLine: Line              = Line(Vector(this))
    def *(n: Int)                 = copy(text = text * n)

    def bold: Fragment =
      copy(style = Style.Bold(self))

    def underlined: Fragment =
      copy(style = Style.Underlined(self))

    def ansi(ansiColor: String): Fragment =
      copy(style = Style.Ansi(self, ansiColor))
  }
  object Fragment {
    sealed trait Style
    object Style {
      case object Primary                                    extends Style
      case object Default                                    extends Style
      case object Warning                                    extends Style
      case object Error                                      extends Style
      case object Info                                       extends Style
      case object Detail                                     extends Style
      case object Dimmed                                     extends Style
      final case class Bold(fr: Fragment)                    extends Style
      final case class Underlined(fr: Fragment)              extends Style
      final case class Ansi(fr: Fragment, ansiColor: String) extends Style
    }
  }
}
