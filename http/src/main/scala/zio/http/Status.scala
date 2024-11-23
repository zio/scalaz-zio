package zio.http

sealed trait Status
object Status {
  case object OK extends Status
  case object NotModified extends Status
  case object Unauthorized extends Status
} 