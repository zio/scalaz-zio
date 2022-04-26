package zio.test

import zio._

object MultiCMinimalSpec extends ZIOSpecDefault {
  override def spec = suite("MultiSpec")(
    suite("fast inner suite")(
      test("fast test 1") {
        assertTrue(false)
      },
      test("fast test 2") {
        assertTrue(true)
      }
    ),
    suite("slow suite")(
      test("slow 1") {
        Live.live(ZIO.sleep(1.second)).map(_ => assertTrue(true))
      },
      test("slow 2") {
        Live.live(ZIO.sleep(3.second)).map(_ => assertTrue(true))
      }
    )
  ) @@ TestAspect.ignore
}

object SmallMinimalSpec extends ZIOSpecDefault {
  override def spec = suite("SmallMultiSpec")(
    suite("fast inner suite")(
      test("fast test 1") {
        assertTrue(true)
      }
    )
  ) @@ TestAspect.ignore
}

object SlowMinimalSpec extends ZIOSpecDefault {
  override def spec = suite("SM")(
    suite("SMFast ")(
      test("SMF 1") {
        Live.live(ZIO.sleep(1.second)).map(_ => assertTrue(true))
      },
      test("SMF 2") {
        Live.live(ZIO.sleep(1.second)).map(_ => assertTrue(true))
      }
    ),
    suite("SMMedium")(
      test("SMM 1") {
        Live.live(ZIO.sleep(1.second)).map(_ => assertTrue(true))
      },
      test("SMM 2") {
        Live.live(ZIO.sleep(2.second)).map(_ => assertTrue(true))
      }
    ),
    suite("SMSlow")(
      test("SMS 1") {
        Live.live(ZIO.sleep(2.second)).map(_ => assertTrue(true))
      },
      test("SMS 2") {
        Live.live(ZIO.sleep(3.second)).map(_ => assertTrue(true))
      }
    )
  ) @@ TestAspect.ignore
}
