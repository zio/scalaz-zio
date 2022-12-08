package zio

import zio.test.TestSystem
import zio.test._

object ConfigProviderEnvSpec extends ZIOBaseSpec {

  final case class HostPort(host: String, port: Int)
  object HostPort {
    val config: Config[HostPort] = (Config.string("host") ++ Config.int("port")).map { case (a, b) => HostPort(a, b) }
  }

  final case class ServiceConfig(hostPort: HostPort, timeout: Int)
  object ServiceConfig {
    val config: Config[ServiceConfig] =
      (HostPort.config.nested("hostPort") ++ Config.int("timeout")).map { case (a, b) => ServiceConfig(a, b) }
  }

  final case class HostPorts(hostPorts: List[HostPort])
  object HostPorts {
    val config: Config[HostPorts] = Config.listOf("hostPorts", HostPort.config).map(HostPorts(_))
  }

  def spec = suite("ConfigProviderEnvSpec") {
    test("flat atoms env") {
      for {
        _      <- TestSystem.putEnv("HOST", "localhost")
        _      <- TestSystem.putEnv("PORT", "8080")
        config <- ConfigProvider.envProvider.load(HostPort.config)
      } yield assertTrue(config == HostPort("localhost", 8080))
    } +
      test("nested atoms env") {
        for {
          _      <- TestSystem.putEnv("HOSTPORT_HOST", "localhost")
          _      <- TestSystem.putEnv("HOSTPORT_PORT", "8080")
          _      <- TestSystem.putEnv("TIMEOUT", "1000")
          config <- ConfigProvider.envProvider.load(ServiceConfig.config)
        } yield assertTrue(config == ServiceConfig(HostPort("localhost", 8080), 1000))
      } +
      test("top-level list env") {
        for {
          _      <- TestSystem.putEnv("HOSTPORTS_HOST", "localhost,localhost,localhost")
          _      <- TestSystem.putEnv("HOSTPORTS_PORT", "8080,8080,8080")
          config <- ConfigProvider.envProvider.load(HostPorts.config)
        } yield assertTrue(config.hostPorts.length == 3)
      } +
      test("top-level missing list env") {
        for {
          config <- ConfigProvider.envProvider.load(HostPorts.config)
        } yield assertTrue(config.hostPorts.length == 0)
      } +
      test("simple map env") {
        for {
          _      <- TestSystem.putEnv("NAME", "Sherlock Holmes")
          _      <- TestSystem.putEnv("ADDRESS", "221B Baker Street")
          config <- ConfigProvider.envProvider.load(Config.table(Config.string))
        } yield assertTrue(config == Map("NAME" -> "Sherlock Holmes", "ADDRESS" -> "221B Baker Street"))
      }
  }
}
