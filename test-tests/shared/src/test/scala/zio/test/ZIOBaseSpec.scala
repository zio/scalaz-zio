package zio.test

import zio._

trait ZIOBaseSpec extends ZIOSpecDefault {
  override def aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    if (TestPlatform.isJVM) Chunk(TestAspect.timeout(120.seconds), TestAspect.timed)
    else if (TestPlatform.isNative) Chunk(TestAspect.timeout(120.seconds), TestAspect.timed, TestAspect.size(10))
    else Chunk(TestAspect.timeout(120.seconds), TestAspect.sequential, TestAspect.timed, TestAspect.size(10))
}
