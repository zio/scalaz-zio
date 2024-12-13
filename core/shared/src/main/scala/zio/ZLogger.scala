package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace
import izumi.reflect.macrortti.LightTypeTag

trait ZLogger[-Message, +Output] { self =>
  def apply(
    trace: Trace,
    fiberId: FiberId,
    logLevel: LogLevel,
    message: () => Message,
    cause: Cause[Any],
    context: FiberRefs,
    spans: List[LogSpan],
    annotations: Map[String, String]
  ): Output

  /**
   * Combines this logger with the specified logger to produce a new logger that
   * logs to both this logger and that logger.
   */
  def ++[M <: Message, O](
    that: ZLogger[M, O]
  )(implicit zippable: Zippable[Output, O]): ZLogger[M, zippable.Out] =
    new ZLogger[M, zippable.Out] {
      def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => M,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
      ): zippable.Out =
        zippable.zip(
          self(trace, fiberId, logLevel, message, cause, context, spans, annotations),
          that(trace, fiberId, logLevel, message, cause, context, spans, annotations)
        )
    }

  def +>[M <: Message, O](that: ZLogger[M, O]): ZLogger[M, O] = (self ++ that).map(_._2)

  def <+[M <: Message](that: ZLogger[M, Any]): ZLogger[M, Output] = (self ++ that).map(_._1)

  final def contramap[Message1](f: Message1 => Message): ZLogger[Message1, Output] =
    new ZLogger[Message1, Output] {
      def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => Message1,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
      ): Output = self(trace, fiberId, logLevel, () => f(message()), cause, context, spans, annotations)
    }

  /**
   * Returns a version of this logger that only logs messages when the log level
   * satisfies the specified predicate.
   */
  final def filterLogLevel(f: LogLevel => Boolean): ZLogger[Message, Option[Output]] =
    new ZLogger[Message, Option[Output]] {
      def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => Message,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
      ): Option[Output] =
        if (f(logLevel)) {
          Some(self(trace, fiberId, logLevel, message, cause, context, spans, annotations))
        } else None
    }

  final def map[B](f: Output => B): ZLogger[Message, B] =
    new ZLogger[Message, B] {
      def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => Message,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
      ): B = f(self(trace, fiberId, logLevel, message, cause, context, spans, annotations))
    }

  final def test(input: => Message): Output =
    apply(
      Trace.empty,
      FiberId.None,
      LogLevel.Info,
      () => input,
      Cause.empty,
      FiberRefs.empty,
      Nil,
      Map.empty
    )
}
object ZLogger {
  private[zio] val stringTag: LightTypeTag = EnvironmentTag[String].tag
  private[zio] val causeTag: LightTypeTag  = EnvironmentTag[Cause[Any]].tag

  import Predef.{Set => ScalaSet, _}

  val default: ZLogger[String, String] = (
    trace: Trace,
    fiberId: FiberId,
    logLevel: LogLevel,
    message0: () => String,
    cause: Cause[Any],
    context: FiberRefs,
    spans0: List[LogSpan],
    annotations: Map[String, String]
  ) => {
    // For why 256 here, see https://github.com/zio/zio/pull/9416#discussion_r1886208534
    val sb = new StringBuilder(256)

    val _ = context

    val now = java.time.Instant.now()

    sb.append("timestamp=")
      .append(now.toString())
      .append(" level=")
      .append(logLevel.label)
      .append(" thread=#")
      .append(fiberId.threadName)
      .append(" message=\"")
      .append(message0())
      .append("\"")

    if ((cause ne null) && (cause ne Cause.empty)) {
      sb.append(" cause=\"")
        .append(cause.prettyPrint)
        .append("\"")
    }

    if (spans0.nonEmpty) {
      val nowMillis = java.lang.System.currentTimeMillis()

      sb.append(" ")

      val it    = spans0.iterator
      var first = true

      while (it.hasNext) {
        if (first) {
          first = false
        } else {
          sb.append(" ")
        }

        it.next().renderInto(sb, nowMillis)(Unsafe.unsafe)
      }
    }

    val parsedTarce = Trace.parseOrNull(trace)
    if (parsedTarce ne null) {
      val location = parsedTarce._1
      val file     = parsedTarce._2
      val line     = parsedTarce._3

      sb.append(" location=")
      appendQuoted(location, sb)
      sb.append(" file=")
      appendQuoted(file, sb)
      sb.append(" line=").append(line)
    }

    if (annotations.nonEmpty) {
      sb.append(" ")

      val it    = annotations.iterator
      var first = true

      while (it.hasNext) {
        if (first) {
          first = false
        } else {
          sb.append(" ")
        }

        val (key, value) = it.next()

        appendQuoted(key, sb)
        sb.append("=")
        appendQuoted(value, sb)
      }
    }

    sb.toString()
  }

  /**
   * A logger that does nothing in response to logging events.
   */
  val none: ZLogger[Any, Unit] = new ZLogger[Any, Unit] {
    def apply(
      trace: Trace,
      fiberId: FiberId,
      logLevel: LogLevel,
      message: () => Any,
      cause: Cause[Any],
      context: FiberRefs,
      spans: List[LogSpan],
      annotations: Map[String, String]
    ): Unit =
      ()
  }

  def simple[A, B](log: A => B): ZLogger[A, B] =
    new ZLogger[A, B] {
      def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => A,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
      ): B =
        log(message())
    }

  def succeed[A](a: => A): ZLogger[Any, A] = simple(_ => a)

  private def appendQuoted(label: String, sb: StringBuilder): StringBuilder = {
    if (label.indexOf(" ") < 0) sb.append(label)
    else sb.append("\"").append(label).append("\"")
    sb
  }
}
