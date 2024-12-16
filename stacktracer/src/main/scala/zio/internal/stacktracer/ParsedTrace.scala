package zio.internal.stacktracer

private[zio] final case class ParsedTrace(
  location: String,
  file: String,
  line: Int
)
