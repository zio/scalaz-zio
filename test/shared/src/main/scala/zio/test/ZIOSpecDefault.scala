package zio.test

import zio._
import zio.internal.stacktracer.Tracer
import zio.internal.stacktracer.Tracer.{instance, newTrace}
import zio.stacktracer.TracingImplicits.disableAutoTrace

abstract class ZIOSpecDefault extends ZIOSpec[TestEnvironment] {

  override val bootstrap: ZLayer[ZIOAppArgs with Scope, Any, TestEnvironment] = {
    implicit val trace: zio.Trace = Tracer.newTrace
    zio.ZEnv.live >>> TestEnvironment.live
  }

  def spec: Spec[TestEnvironment with Scope, Any]
}
