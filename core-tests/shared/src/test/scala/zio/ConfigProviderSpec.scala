package zio

import zio.test._
import zio.test.Assertion._

object ConfigProviderSpec extends ZIOBaseSpec {
  def provider(map: Map[String, String]): ConfigProvider = ConfigProvider.fromMap(map)
  def propsProvider(props: Map[String, String]): UIO[ConfigProvider] =
    ZIO
      .foreachDiscard(props) { case (key, value) =>
        TestSystem.putProperty(key, value)
      }
      .as(ConfigProvider.propsProvider)

  final case class HostPort(host: String, port: Int)
  object HostPort {
    val config: Config[HostPort] = (Config.string("host") ++ Config.int("port")).map { case (a, b) => HostPort(a, b) }

    val default: HostPort = HostPort("localhost", 8080)
  }

  final case class ServiceConfig(hostPort: HostPort, timeout: Int)
  object ServiceConfig {
    val config: Config[ServiceConfig] =
      (HostPort.config.nested("hostPort") ++ Config.int("timeout")).map { case (a, b) => ServiceConfig(a, b) }

    val default: ServiceConfig = ServiceConfig(HostPort.default, 1000)
  }

  final case class HostPorts(hostPorts: List[HostPort])
  object HostPorts {
    val config: Config[HostPorts] = Config.listOf("hostPorts", HostPort.config).map(HostPorts(_))

    val default: HostPorts = HostPorts(List(HostPort.default))
  }

  final case class StockDay(
    date: java.time.LocalDate,
    open: BigDecimal,
    close: BigDecimal,
    low: BigDecimal,
    high: BigDecimal,
    volume: BigInt
  )
  object StockDay {
    val config: Config[StockDay] =
      (Config.localDate("date") ++ Config.bigDecimal("open") ++ Config.bigDecimal("close") ++ Config.bigDecimal(
        "low"
      ) ++ Config.bigDecimal("high") ++ Config.bigInt("volume")).map { case (a, b, c, d, e, f) =>
        StockDay(a, b, c, d, e, f)
      }

    val default: StockDay = StockDay(java.time.LocalDate.of(2022, 10, 28), 98.8, 150.0, 98.0, 151.5, 100091990)
  }

  final case class SNP500(stockDays: Map[String, StockDay])
  object SNP500 {
    val config: Config[SNP500] = Config.table(StockDay.config).map(SNP500(_))

    val default: SNP500 = SNP500(Map("ZIO" -> StockDay.default))

    val empty: SNP500 = SNP500(Map.empty)
  }

  final case class WebScrapingTargets(targets: Set[java.net.URI])
  object WebScrapingTargets {
    val config: Config[WebScrapingTargets] = (Config.setOf("targets", Config.uri)).map(WebScrapingTargets(_))

    val default: WebScrapingTargets = WebScrapingTargets(
      Set(new java.net.URI("https://zio.dev"), new java.net.URI("https://github.com/zio"))
    )
  }

  def spec = suite("ConfigProviderSpec") {
    suite("map")(
      test("flat atoms") {
        for {
          value <- provider(Map("host" -> "localhost", "port" -> "8080")).load(HostPort.config)
        } yield assertTrue(value == HostPort.default)
      } +
        test("nested atoms") {
          for {
            value <- provider(Map("hostPort.host" -> "localhost", "hostPort.port" -> "8080", "timeout" -> "1000"))
                       .load(ServiceConfig.config)
          } yield assertTrue(value == ServiceConfig.default)
        } +
        test("top-level list with same number of elements per key") {
          for {
            value <-
              provider(Map("hostPorts.host" -> "localhost,localhost,localhost", "hostPorts.port" -> "8080,8080,8080"))
                .load(HostPorts.config)
          } yield assertTrue(value.hostPorts.length == 3)
        } +
        test("top-level missing list") {
          for {
            exit <- provider(Map()).load(HostPorts.config).exit
          } yield assert(exit)(failsWithA[Config.Error])
        } +
        test("simple map") {
          for {
            value <- provider(Map("name" -> "Sherlock Holmes", "address" -> "221B Baker Street"))
                       .load(Config.table(Config.string))
          } yield assertTrue(value == Map("name" -> "Sherlock Holmes", "address" -> "221B Baker Street"))
        } +
        test("top-level lists with multi-character sequence delimiters") {
          for {
            value <-
              ConfigProvider
                .fromMap(
                  Map(
                    "hostPorts.host" -> "localhost///localhost///localhost",
                    "hostPorts.port" -> "8080///8080///8080"
                  ),
                  seqDelim = "///"
                )
                .load(HostPorts.config)
          } yield assertTrue(value.hostPorts.length == 3)
        } +
        test("top-level lists with special regex multi-character sequence delimiter") {
          for {
            value <-
              ConfigProvider
                .fromMap(
                  Map(
                    "hostPorts.host" -> "localhost|||localhost|||localhost",
                    "hostPorts.port" -> "8080|||8080|||8080"
                  ),
                  seqDelim = "|||"
                )
                .load(HostPorts.config)
          } yield assertTrue(value.hostPorts.length == 3)
        } +
        test("top-level lists with special regex character sequence delimiter") {
          for {
            value <-
              ConfigProvider
                .fromMap(
                  Map("hostPorts.host" -> "localhost*localhost*localhost", "hostPorts.port" -> "8080*8080*8080"),
                  seqDelim = "*"
                )
                .load(HostPorts.config)
          } yield assertTrue(value.hostPorts.length == 3)
        } +
        test("top-level list with different number of elements per key fails") {
          for {
            exit <-
              provider(Map("hostPorts.host" -> "localhost", "hostPorts.port" -> "8080,8080,8080"))
                .load(HostPorts.config)
                .exit
          } yield assert(exit)(Assertion.failsWithA[Config.Error])
        } +
        test("flat atoms of different types") {
          for {
            value <- provider(
                       Map(
                         "date"   -> "2022-10-28",
                         "open"   -> "98.8",
                         "close"  -> "150.0",
                         "low"    -> "98.0",
                         "high"   -> "151.5",
                         "volume" -> "100091990"
                       )
                     ).load(StockDay.config)
          } yield assertTrue(value == StockDay.default)
        } +
        test("tables") {
          for {
            value <- provider(
                       Map(
                         "ZIO.date"   -> "2022-10-28",
                         "ZIO.open"   -> "98.8",
                         "ZIO.close"  -> "150.0",
                         "ZIO.low"    -> "98.0",
                         "ZIO.high"   -> "151.5",
                         "ZIO.volume" -> "100091990"
                       )
                     ).load(SNP500.config)
          } yield assertTrue(value == SNP500.default)
        } +
        test("empty tables") {
          for {
            value <- provider(Map()).load(SNP500.config)
          } yield assertTrue(value == SNP500.empty)
        } +
        test("collection of atoms") {
          for {
            value <-
              provider(Map("targets" -> "https://zio.dev,https://github.com/zio")).load(WebScrapingTargets.config)
          } yield assertTrue(value == WebScrapingTargets.default)
        } +
        test("accessing a non-existent key fails") {
          for {
            exit <- provider(Map("k1.k3" -> "v")).load(Config.string("k2").nested("k1")).exit
          } yield assert(exit)(Assertion.failsWithA[Config.Error])
        } +
        test("accessing a constant value succeeds") {
          for {
            value <- provider(Map()).load(Config.succeed("value"))
          } yield assertTrue(value == "value")
        }
    ) + suite("props")(
      test("flat atoms") {
        for {
          provider <- propsProvider(Map("host" -> "localhost", "port" -> "8080"))
          result   <- provider.load(HostPort.config)
        } yield assertTrue(result == HostPort.default)
      },
      test("nested atoms") {
        for {
          provider <-
            propsProvider(Map("hostPort.host" -> "localhost", "hostPort.port" -> "8080", "timeout" -> "1000"))
          result <- provider.load(ServiceConfig.config)
        } yield assertTrue(result == ServiceConfig.default)
      },
      test("top-level list") {
        for {
          provider <- propsProvider(
                        Map("hostPorts.host" -> "localhost,localhost,localhost", "hostPorts.port" -> "8080,8080,8080")
                      )
          result <- provider.load(HostPorts.config)
        } yield assertTrue(result.hostPorts.length == 3)
      },
      test("top-level missing list") {
        for {
          provider <- propsProvider(Map())
          exit     <- provider.load(HostPorts.config).exit
        } yield assert(exit)(failsWithA[Config.Error])
      },
      test("simple map") {
        for {
          provider <- propsProvider(Map("name" -> "Sherlock Holmes", "address" -> "221B Baker Street"))
          result   <- provider.load(Config.table(Config.string))
        } yield assertTrue(result == Map("name" -> "Sherlock Holmes", "address" -> "221B Baker Street"))
      },
      test("empty property name") {
        for {
          provider <- propsProvider(Map("" -> "42.24"))

          result2 <- provider.load(Config.double)
          result1 <- provider.load(Config.double(""))
        } yield assertTrue(result1 == 42.24, result2 == 42.24)
      },
      test("path delimiter property name") {
        for {
          provider <- propsProvider(Map("." -> "42", ".." -> "24"))

          result1 <- provider.load(Config.int("."))
          result2 <- provider.load(Config.int(".."))
        } yield assertTrue(result1 == 42, result2 == 24)
      },
      test("incorrect path property name") {
        for {
          provider <- propsProvider(Map(".a" -> "42"))

          result1 <- provider.load(Config.int(".a"))
          result2 <- provider.load(Config.int("a").nested(""))
        } yield assertTrue(result1 == 42, result2 == 42)
      },
      test("fail for non symmetric top-level list") {
        for {
          provider <- propsProvider(
                        Map("hostPorts.host" -> "localhost", "hostPorts.port" -> "8080,8080")
                      )
          result <- provider.load(HostPorts.config).flip.exit
        } yield assertTrue(
          result.exists(
            _ == Config.Error.MissingData(
              path = Chunk("hostPorts"),
              message = "The element at index 1 in a sequence at hostPorts was missing"
            )
          )
        )
      },
      test("fail for missing property") {
        for {
          provider <- propsProvider(Map.empty)
          result   <- provider.load(Config.string("key")).flip.exit
        } yield assertTrue(
          result.exists(
            _ == Config.Error.MissingData(
              path = Chunk("key"),
              message = "Expected key to be set in properties"
            )
          )
        )
      },
      test("fail for wrong property type") {
        for {
          provider <- propsProvider(Map("key" -> "value"))
          result   <- provider.load(Config.int("key")).flip.exit
        } yield assertTrue(
          result.exists(
            _ == Config.Error.InvalidData(
              path = Chunk("key"),
              message = "Expected an integer value, but found value"
            )
          )
        )
      },
      test("succeed for constant value") {
        for {
          provider <- propsProvider(Map())
          result   <- provider.load(Config.succeed("value"))
        } yield assertTrue(result == "value")
      }
    ) +
      test("nested") {
        val configProvider1 = ConfigProvider.fromMap(Map("nested.key" -> "value"))
        val config1         = Config.string("key").nested("nested")
        val configProvider2 = ConfigProvider.fromMap(Map("nested.key" -> "value")).nested("nested")
        val config2         = Config.string("key")
        for {
          result1 <- configProvider1.load(config1)
          result2 <- configProvider2.load(config2)
        } yield assertTrue(result1 == "value") && assertTrue(result2 == "value")
      } +
      test("nested with multiple layers of nesting") {
        val configProvider1 = ConfigProvider.fromMap(Map("parent.child.key" -> "value"))
        val config1         = Config.string("key").nested("child").nested("parent")
        val configProvider2 =
          ConfigProvider.fromMap(Map("parent.child.key" -> "value")).nested("child").nested("parent")
        val config2 = Config.string("key")
        for {
          result1 <- configProvider1.load(config1)
          result2 <- configProvider2.load(config2)
        } yield assertTrue(result1 == "value") && assertTrue(result2 == "value")
      } +
      test("orElse") {
        for {
          _      <- TestSystem.putProperty("key", "value")
          result <- ZIO.config(Config.string("key").optional)
        } yield assertTrue(result == Some("value"))
      } +
      test("values are not split unless a sequence is expected") {
        for {
          _      <- TestSystem.putProperty("greeting", "Hello, World!")
          result <- ZIO.config(Config.string("greeting"))
        } yield assertTrue(result == "Hello, World!")
      } +
      test("secret") {
        for {
          _      <- TestSystem.putProperty("greeting", "Hello, World!")
          result <- ZIO.config(Config.secret.nested("greeting"))
        } yield assertTrue(result == Config.Secret("Hello, World!"))
      } +
      test("contramapPath") {
        val configProvider = ConfigProvider.fromMap(Map("KEY" -> "VALUE")).contramapPath(_.toUpperCase)
        for {
          result <- configProvider.load(Config.string("key"))
        } yield assertTrue(result == "VALUE")
      } +
      test("unnested") {
        val configProvider1 = ConfigProvider.fromMap(Map("key" -> "value"))
        val config1         = Config.string("key")
        val configProvider2 = ConfigProvider.fromMap(Map("key" -> "value")).unnested("nested")
        val config2         = Config.string("key").nested("nested")
        for {
          result1 <- configProvider1.load(config1)
          result2 <- configProvider2.load(config2)
        } yield assertTrue(result1 == "value") && assertTrue(result2 == "value")
      } +
      test("unnested with multiple layers of nesting") {
        val configProvider1 = ConfigProvider.fromMap(Map("key" -> "value"))
        val config1         = Config.string("key")
        val configProvider2 = ConfigProvider.fromMap(Map("key" -> "value")).unnested("parent").unnested("child")
        val config2         = Config.string("key").nested("child").nested("parent")
        for {
          result1 <- configProvider1.load(config1)
          result2 <- configProvider2.load(config2)
        } yield assertTrue(result1 == "value") && assertTrue(result2 == "value")
      } +
      test("unnested failure") {
        val configProvider = ConfigProvider.fromMap(Map("key" -> "value")).unnested("nested")
        val config         = Config.string("key")
        for {
          result <- configProvider.load(config).exit
        } yield assert(result)(Assertion.failsWithA[Config.Error])
      } +
      test("within") {
        val configProvider =
          ConfigProvider
            .fromMap(Map("nesting1.key1" -> "value1", "nesting2.KEY2" -> "value2"))
            .within(Chunk("nesting2"))(_.contramapPath(_.toUpperCase))
        val config = Config.string("key1").nested("nesting1").zip(Config.string("key2").nested("nesting2"))
        for {
          result <- configProvider.load(config)
        } yield assertTrue(result == "value1" -> "value2")
      } +
      test("within with multiple layers of nesting") {
        val configProvider =
          ConfigProvider
            .fromMap(Map("nesting1.key1" -> "value1", "nesting2.nesting3.KEY2" -> "value2"))
            .within(Chunk("nesting2", "nesting3"))(_.contramapPath(_.toUpperCase))
        val config =
          Config.string("key1").nested("nesting1").zip(Config.string("key2").nested("nesting3").nested("nesting2"))
        for {
          result <- configProvider.load(config)
        } yield assertTrue(result == "value1" -> "value2")
      } +
      test("kebabCase") {
        val configProvider = ConfigProvider.fromMap(Map("kebab-case" -> "value")).kebabCase
        val config         = Config.string("kebabCase")
        for {
          result <- configProvider.load(config)
        } yield assertTrue(result == "value")
      } +
      test("lowerCase") {
        val configProvider = ConfigProvider.fromMap(Map("lowercase" -> "value")).lowerCase
        val config         = Config.string("lowerCase")
        for {
          result <- configProvider.load(config)
        } yield assertTrue(result == "value")
      } +
      test("snakeCase") {
        val configProvider = ConfigProvider.fromMap(Map("snake_case" -> "value")).snakeCase
        val config         = Config.string("snakeCase")
        for {
          result <- configProvider.load(config)
        } yield assertTrue(result == "value")
      } +
      test("upperCase") {
        val configProvider = ConfigProvider.fromMap(Map("UPPERCASE" -> "value")).upperCase
        val config         = Config.string("upperCase")
        for {
          result <- configProvider.load(config)
        } yield assertTrue(result == "value")
      }
  }
}
