package zio.http

sealed trait Method
object Method {
  case object GET extends Method
  case object POST extends Method
  case object HEAD extends Method
} 