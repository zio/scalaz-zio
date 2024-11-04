---
id: articles 
title: "Articles"
---

:::caution
These articles reflect the state of ZIO at the time of their publication. The code samples might be outdated, considering ZIO was early in development at the time they were written. However, the concepts are still relevant.
:::

## ZIO Core
- [Wrapping impure code with ZIO](https://medium.com/@ghostdogpr/wrapping-impure-code-with-zio-9265c219e2e) by Pierre Ricadat (July 2019)
- [Thread shifting in cats-effect and ZIO](https://blog.softwaremill.com/thread-shifting-in-cats-effect-and-zio-9c184708067b) by Adam Warski (June 2019)
- [Performant Functional Programming to the Max with ZIO](https://cloudmark.github.io/A-Journey-To-Zio/) by Mark Galea (May 2019)
- [ZIO & Cats Effect: A Match Made in Heaven](http://degoes.net/articles/zio-cats-effect) by John De Goes (April 2019)
- [Beautiful, Simple, Testable Functional Effects for Scala](http://degoes.net/articles/zio-environment) (introducing ZIO Environment) by John De Goes (February 2019)
- [Thread Pool Best Practices with ZIO](http://degoes.net/articles/zio-threads) by John De Goes (January 2019)
- [Bifunctor IO: A Step Away from Dynamically-Typed Error Handling](http://degoes.net/articles/bifunctor-io) by John De Goes (May 2018)

## Patterns and Best Practices
- [Idiomatic dependency injection for ZIO applications in Scala](https://blog.pierre-ricadat.com/idiomatic-dependency-injection-for-zio-applications-in-scala) by Pierre Ricadat (Nov 2024)
- [The Tri-Z Architecture: a Pattern for Layering ZIO Applications in Scala](https://blog.pierre-ricadat.com/the-tri-z-architecture-a-pattern-for-layering-zio-applications-in-scala) by Pierre Ricadat (Aug 2024)
- [Tuning ZIO for high performance](https://blog.pierre-ricadat.com/tuning-zio-for-high-performance) by Pierre Ricadat (May 2024)
- [Processing ZIO effects through a pipeline](https://medium.com/@svroonland/processing-zio-effects-through-a-pipeline-c469e28dff62) (September 2020)
- [5 pitfalls to avoid when starting to work with ZIO](https://medium.com/wix-engineering/5-pitfalls-to-avoid-when-starting-to-work-with-zio-adefdc7d2d5c) by Natan Silnitsky (Jan 2020)

## ZIO STM
- [How to write a concurrent LRU cache with ZIO STM](https://scalac.io/how-to-write-a-completely-lock-free-concurrent-lru-cache-with-zio-stm/) by Jorge Vasquez (March 2020)
- [Exploring the STM functionality in ZIO](https://freskog.github.io/blog/2019/05/30/explore-zio-stm/) by Fredrik Skogberg (May 2019)

## Testing
- [Integration Testing](https://blended-zio.github.io/blended-zio/blog/integration-testing) by Andreas Gies (Feb 2021)
- [Effective testing with ZIO Test (RC18)](https://scala.monster/zio-test/) by Pavels Sisojevs (April 2020)
- [Testing background process with ZIO](https://www.rudder.io/blog/testing-background-process-zio/) by François Armand (March 2020)
- [Effective testing with ZIO Test (RC17)](https://scala.monster/zio-test-old/) by Pavels Sisojevs (January 2020)
- [Speeding up time with ZIO TestClock](https://timpigden.github.io/_pages/zio-streams/SpeedingUpTime.html) by Tim Pigden (October 2019)
- [Testing Incrementally with ZIO Environment](http://degoes.net/articles/testable-zio) by John De Goes (March 2019)

## ZIO Streams
- [ZIO Streams and JMS](https://blended-zio.github.io/blended-zio/blog/zio-streams-jms) by Andreas Gies (October 2020)
- [Building the Death Star with ZIO Stream](https://juliano-alves.com/2020/05/04/deathstar-zio-stream/) by Juliano Alves (May 2020)
- [Simulating IoT Events with ZIO Streams](https://timpigden.github.io/_pages/zio-streams/GeneratingChillEvents.html) by Tim Pigden (November 2019)

## ZIO Service Pattern
- [Example of ZLayers being used in combination](https://timpigden.github.io/_pages/zlayer/Examples.html) by Tim Pigden (March 2020)
- [From idea to product with ZLayer](https://scala.monster/welcome-zio/) by Pavels Sisojevs (March 2020)
- [What are the benefits of the ZIO modules with ZLayers](https://medium.com/@pascal.mengelt/what-are-the-benefits-of-the-zio-modules-with-zlayers-3bf6cc064a9b) by Pascal Mengelt (March 2020)
- [Decouple the Program from its Implementation with ZIO modules.](https://medium.com/@pascal.mengelt/decouple-the-program-from-its-implementation-with-zio-modules-d9b8713d502e) by Pascal Mengelt (December 2019)
- [Functional dependency injection in Scala using ZIO environments](https://blog.jdriven.com/2019/10/functional-dependency-injection-in-scala-using-zio-environments/) by Chiel van de Steeg (October 2019)

## ZIO Use Cases
- [Spring to ZIO 101 - ZIO CRUD](https://adrianfilip.com/2020/03/15/spring-to-zio-101/) by Adrian Filip (March 2020)
- [Implement your future with ZIO](https://scala.monster/welcome-zio-old/) by Pavels Sisojevs (December 2019)
- [How to write a command line application with ZIO?](https://scalac.io/write-command-line-application-with-zio/) by Piotr Gołębiewski (November 2019)
- [Hacker News API Part 5](http://justinhj.github.io/2019/04/07/hacker-news-api-5.html) by Justin Heyes-Jones (April 2019)
- [Building the Hangman Game using ScalaZ ZIO](https://abhsrivastava.github.io/2018/11/03/Hangman-Game-Using-ZIO/) by Abhishek Srivastava (November 2018)
- [Elevator Control System using ZIO](https://medium.com/@wiemzin/elevator-control-system-using-zio-c718ae423c58) by Wiem Zine El Abidine (September 2018)

## Integration with Other Libraries
- [Streaming microservices with ZIO and Kafka](https://scalac.io/streaming-microservices-with-zio-and-kafka/) by Aleksandar Skrbic (February 2021)
- [uzhttp + sttp for light-weight http and websockets](https://timpigden.github.io/_pages/zio-uzhttp-sttp/uzhttp-sttp.html) updated for 1.0.1 by Tim Pigden (August 2020)
- [Streaming all the way with ZIO, Doobie, Quill, http4s and fs2](https://juliano-alves.com/2020/06/15/streaming-all-the-way-zio-doobie-quill-http4s-fs2/) by Juliano Alves (June 2020)
- [Building a cool CLI with Decline for my ZIO App](https://medium.com/@pascal.mengelt/building-a-cool-cli-with-decline-for-my-zio-app-80e095b2899a) by Pascal Mengelt (May 2020)
- [ZIO with http4s, Auth, Codecs and zio-tests (RC18)](https://timpigden.github.io/_pages/zio-http4s/intro.html) by Tim Pigden (April 2020)
- [An Introduction to ZIO Kafka](https://www.ziverge.com/post/an-introduction-to-zio-kafka) by Ziverge (April 2020)
- [ZIO + Http4s: a simple API client](https://juliano-alves.com/2020/04/20/zio-http4s-a-simple-api-client/) by Juliano Alves (April 2020)
- [What can ZIO do for me? A Long Polling example with sttp.](https://medium.com/@pascal.mengelt/what-can-zio-do-for-me-32281e4e8b16) by Pascal Mengelt (November 2019)
- [Making ZIO, Akka and Slick play together nicely](https://scalac.io/making-zio-akka-slick-play-together-nicely-part-1-zio-and-slick/) by Jakub Czuchnowski (August 2019)
- [Combining ZIO and Akka to enable distributed FP in Scala](https://medium.com/@ghostdogpr/combining-zio-and-akka-to-enable-distributed-fp-in-scala-61ffb81e3283) by Pierre Ricadat (July 2019)
- [tAPIr’s Endpoint meets ZIO’s IO](https://blog.softwaremill.com/tapirs-endpoint-meets-zio-s-io-3278099c5e10) by Adam Warski (July 2019)
- [ZIO with http4s and doobie](https://medium.com/@wiemzin/zio-with-http4s-and-doobie-952fba51d089) by Wiem Zine El Abidine (June 2019)
- [Using 47 Degree's Fetch library with ZIO](http://justinhj.github.io/2019/05/05/using-47degs-fetch-with-zio.html) by Justin Heyes-Jones (May 2019)

## Contribution
- [Lessons Learned From Being a ZIO Contributor](https://www.softinio.com/post/lessons-learned-from-being-a-zio-contributor/) by [Salar Rahmanian](https://www.softinio.com) (September 2020)

## Benchmarking and Comparison
- [Benchmarking Functional Error Handling in Scala](https://www.iteratorshq.com/blog/benchmarking-functional-error-handling-in-scala/) by Marcin Rzeźnicki (April 2020)
- [Scalaz 8 IO vs Akka (typed) Actors vs Monix (part 1)](https://blog.softwaremill.com/scalaz-8-io-vs-akka-typed-actors-vs-monix-part-1-5672657169e1) + [part 2](https://blog.softwaremill.com/akka-vs-zio-vs-monix-part-2-communication-9ce7261aa08c) + [part 3](https://blog.softwaremill.com/supervision-error-handling-in-zio-akka-and-monix-part-3-series-summary-abe75f964c2a) by Adam Warski (June 2018)