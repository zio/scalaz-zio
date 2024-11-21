package zio.stacktracer

object TracingImplicits {

  implicit val disableAutoTrace: DisableAutoTrace = new DisableAutoTrace {}
}

sealed trait DisableAutoTrace
