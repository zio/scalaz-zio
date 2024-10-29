package zio

import zio.test._
import zio.test.Assertion._

object ConfigProviderAppArgsSpec extends ZIOBaseSpec {

  final case class HostPort(host: String, port: Int)
  object HostPort {
    val config: Config[HostPort] = (Config.string("host") ++ Config.int("port")).map { case (a, b) => HostPort(a, b) }
  }

  final case class ServiceConfig(hostPort: HostPort, timeout: Int)
  object ServiceConfig {
    implicit val config: Config[ServiceConfig] =
      (HostPort.config.nested("hostPort") ++ Config.int("timeout")).map { case (a, b) => ServiceConfig(a, b) }
  }

  final case class HostPorts(hostPorts: List[HostPort])
  object HostPorts {
    val config: Config[HostPorts] = Config.listOf("hostPorts", HostPort.config).map(HostPorts(_))
  }

  def spec = suite("ConfigProviderAppArgsSpec") {
    test("flat atoms args") {
      val args = Chunk("--host", "localhost", "--port=8080")
      for {
        config <- ConfigProvider.fromAppArgs(ZIOAppArgs(args)).load(HostPort.config)
      } yield assertTrue(config == HostPort("localhost", 8080))
    } +
      test("nested atoms args") {
        val args = Chunk("--hostPort.host", "localhost", "--hostPort.port", "8080", "--timeout", "1000")
        for {
          config <- ConfigProvider.fromAppArgs(ZIOAppArgs(args)).load[ServiceConfig]
        } yield assertTrue(config == ServiceConfig(HostPort("localhost", 8080), 1000))
      } +
      test("top-level list args") {
        val args = Chunk(
          "--hostPorts.host",
          "localhost",
          "localhost",
          "--hostPorts.host=localhost",
          "--hostPorts.port",
          "8080",
          "8080",
          "8080"
        )
        for {
          config <- ConfigProvider.fromAppArgs(ZIOAppArgs(args)) load (HostPorts.config)
        } yield assertTrue(config.hostPorts.length == 3)
      } +
      test("top-level list args with sequence delimiter") {
        val args = Chunk(
          "--hostPorts.host",
          "localhost,localhost",
          "--hostPorts.host=localhost",
          "--hostPorts.port=8080,8080,8080"
        )
        for {
          config <- ConfigProvider.fromAppArgs(ZIOAppArgs(args), seqDelim = Some(",")).load(HostPorts.config)
        } yield assertTrue(config.hostPorts.length == 3)
      } +
      test("top-level missing list arg") {
        for {
          exit <- ConfigProvider.fromAppArgs(ZIOAppArgs(Chunk.empty)).load(HostPorts.config).exit
        } yield assert(exit)(failsWithA[Config.Error])
      } +
      test("simple map args") {
        val args = Chunk("--name", "Sherlock Holmes", "--address", "221B Baker Street")
        for {
          config <- ConfigProvider.fromAppArgs(ZIOAppArgs(args)).load(Config.table(Config.string))
        } yield assertTrue(config == Map("name" -> "Sherlock Holmes", "address" -> "221B Baker Street"))
      } +
      test("nested map args") {
        val args = Chunk("--mappings.abc=error")
        for {
          config <- ConfigProvider.fromAppArgs(ZIOAppArgs(args)).load(Config.table("mappings", Config.string))
        } yield assertTrue(config == Map("abc" -> "error"))
      } +
      test("boolean args") {
        val args = Chunk("--flag")
        for {
          config <- ConfigProvider.fromAppArgs(ZIOAppArgs(args)).load(Config.boolean("flag"))
        } yield assertTrue(config)
      }
  }
}
