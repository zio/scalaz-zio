package zio.test

import zio.internal.ansi.AnsiStringOps
import zio.{Chunk, NonEmptyChunk}
import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * PrettyPrint will attempt to render a Scala value as the syntax used to create
 * that value. This makes it easier to copy-paste from values printed to the
 * console during tests back into runnable code.
 */
private[zio] object PrettyPrint extends PrettyPrintVersionSpecific {
  private val maxListLength = 10
  def apply(any: Any): String =
    any match {
      case nonEmptyChunk: NonEmptyChunk[_] =>
        prettyPrintIterator(nonEmptyChunk.iterator, nonEmptyChunk.size, "NonEmptyChunk")
      case chunk: Chunk[_] =>
        prettyPrintIterator(chunk.iterator, chunk.size, "Chunk")
      case array: Array[_] =>
        prettyPrintIterator(array.iterator, array.length, "Array")

      case Some(a) => s"Some(${PrettyPrint(a)})"
      case None    => s"None"
      case Nil     => "Nil"

      case set: Set[_] =>
        prettyPrintIterator(set.iterator, set.size, className(set))

      case iterable: Seq[_] =>
        prettyPrintIterator(iterable.iterator, iterable.size, className(iterable))

      case map: Map[_, _] =>
        val body = map.map { case (key, value) => s"${PrettyPrint(key)} -> ${PrettyPrint(value)}" }
        s"""Map(
${indent(body.mkString(",\n"))}
)"""

      case product: Product =>
        val name    = product.productPrefix
        val labels0 = labels(product)
        val body = labels0
          .zip(product.productIterator)
          .map { case (key, value) =>
            s"${(key + " =").faint} ${PrettyPrint(value)}"
          }
          .toList
          .mkString(",\n")
        val isMultiline  = body.split("\n").length > 1
        val indentedBody = indent(body, if (isMultiline) 2 else 0)
        val spacer       = if (isMultiline) "\n" else ""
        s"""$name($spacer$indentedBody$spacer)"""

      case string: String =>
        val surround = if (string.split("\n").length > 1) "\"\"\"" else "\""
        string.replace("\"", """\"""").mkString(surround, "", surround)

      case char: Char =>
        s"'${char.toString}'"

      case null => "<null>"

      case other => other.toString
    }

  private def prettyPrintIterator(iterator: Iterator[_], length: Int, className: String): String = {
    val suffix = if (length > maxListLength) {
      s" + ${length - maxListLength} more)"
    } else {
      ")"
    }
    iterator.take(maxListLength).map(PrettyPrint.apply).mkString(s"${className}(", ", ", suffix)
  }

  private def indent(string: String, n: Int = 2): String =
    string.split("\n").map((" " * n) + _).mkString("\n")

  private def className(any: Any): String = any match {
    case _: List[_] => "List"
    case other      => other.getClass.getSimpleName
  }

}
