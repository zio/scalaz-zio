package zio.test.diff

import zio.internal.ansi.AnsiStringOps
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.ConsoleUtils._
import zio.test.PrettyPrint

sealed trait DiffResult {
  self =>

  import DiffResult._

  def noDiff: Boolean = !hasDiff

  def hasDiff: Boolean =
    self match {
      case Nested(_, fields)  => fields.exists(_._2.hasDiff)
      case Different(_, _, _) => true
      case Removed(_)         => true
      case Added(_)           => true
      case Identical(_)       => false
    }

  def render: String = self match {
    case Nested(label, fields) =>
      s"""
$label(
  ${indent(
          fields
            .filter(_._2.hasDiff)
            .map {
              case (Some(label), Different(_, _, Some(custom))) => dim(label + " = ") + indent(custom, label.length + 3)
              case (Some(label), diff) =>
                val color = diff match {
                  case Removed(_) => scala.Console.RED
                  case Added(_)   => scala.Console.GREEN
                  case _          => ""
                }
                dim(color + label + " = ") + diff.render
              case (None, diff) => diff.render
            }
            .mkString(",\n")
        )}
)
         """.trim
    case Different(_, _, Some(custom)) =>
      custom
    case Different(oldValue, newValue, None) =>
      s"${PrettyPrint(oldValue).red} → ${PrettyPrint(newValue).green}"
    case Removed(oldValue) =>
      PrettyPrint(oldValue).red
    case Added(newValue) =>
      PrettyPrint(newValue).green
    case Identical(value) =>
      PrettyPrint(value)
  }
}

object DiffResult {
  case class Nested(label: String, fields: List[(Option[String], DiffResult)]) extends DiffResult

  case class Different(oldValue: Any, newValue: Any, customRender: Option[String] = None) extends DiffResult

  case class Removed(oldValue: Any) extends DiffResult

  case class Added(newValue: Any) extends DiffResult

  case class Identical(value: Any) extends DiffResult

  def indent(string: String, amount: Int = 2): String =
    string.split("\n").toList match {
      case head :: tail =>
        (head +: tail.map((" " * amount) + _)).mkString("\n")
      case other => other.mkString("\n")
    }
}
