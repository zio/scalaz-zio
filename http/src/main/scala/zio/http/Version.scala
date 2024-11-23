package zio.http

sealed trait Version
object Version {
  case object Http_1_0 extends Version
  case object Http_1_1 extends Version
  case object Http_2_0 extends Version
} 