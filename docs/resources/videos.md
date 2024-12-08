---
id: videos 
title:  "Videos"
---

## Functional Programming
- [FP to the Max](https://www.youtube.com/watch?v=sxudIMiOo68) by John De Goes (July 2018)
- [FP to the Min](https://www.youtube.com/watch?v=mrHphQT4RpU) by John De Goes (July 2020)

## Tour of ZIO
- [The ZIO of the Future](https://www.youtube.com/watch?v=u3pgyEiu9eU) by John De Goes (May 2021)
- [A Tour of ZIO](https://www.youtube.com/watch?v=TWdC7DhvD8M) by John De Goes (January 2020)

## ZIO Core
- [The Death of Tagless Final](https://www.youtube.com/watch?v=p98W4bUtbO8) by John De Goes (February 2019)
- [Upgrade Your Future](https://www.youtube.com/watch?v=USgfku1h7Hw) by John De Goes (September 2019)
- [One Monad to Rule Them All](https://www.youtube.com/watch?v=POUEz8XHMhE) by John De Goes (August 2019)
- [Functional Concurrency in Scala with ZIO](https://www.youtube.com/watch?v=m5nas4Hndqo) by Itamar Ravid (June 2019)
- [Thinking Functionally](https://www.youtube.com/watch?v=-KA3BSdqYug) by John De Goes (March 2019)
- [ZIO: Next-Generation Effects in Scala](https://www.youtube.com/watch?v=mkSHhsJXjdc&t=6s) by John De Goes (October 2018)
- [Functional Effects in ZIO](https://www.youtube.com/watch?v=4EeL8-chAR8) by  Wiem Zine El Abidine
- [ZIO WORLD - Blocking](https://www.youtube.com/watch?v=1g21c8VKeuU&t=320s) by Adam Fraser (March 2021) — Adam Fraser presented his work to make ZIO faster with blocking code, showing the tremendous (unbelievable) performance improvements possible with batch-mode blocking; part of ongoing work to make ZIO better at blocking workloads.

## ZIO Runtime System
- [ZIO WORLD - ZIO Runtime System](https://www.youtube.com/watch?v=OFFrw5aJzG4&t=313s) by John A. De Goes (March 2021) — John A. De Goes presented his work on the runtime system for ZIO 2.0, which involves a significant rewrite and simplification of the core execution model, resulting in what will be ZIO's best performance to date; benchmarks show nearly universally faster performance compared to CE3.

## Error Management
- [Error Management: Future vs ZIO](https://www.youtube.com/watch?v=mGxcaQs3JWI) by John De Goes and Kai (May 2019), [slide](https://www.slideshare.net/jdegoes/error-management-future-vs-zio)
- Systematic error management in application - We ported Rudder to ZIO: [video in French](https://www.youtube.com/watch?v=q0PlcgR5M1Q), [slides in English](https://speakerdeck.com/fanf42/systematic-error-management-we-ported-rudder-to-zio) by François Armand (Scala.io, October 2019)

## ZIO Service Pattern
- [ZLayers by example](https://www.youtube.com/watch?v=u5IrfkAo6nk) by Wiem Zine El Abidine (December 2020)
- [ZIO inception: Journey through layers and intersection types](https://www.youtube.com/watch?v=vNQFlq1SvaE) by Vladimir Pavkin (July 2020)
- [ZIO WORLD - ZLayer](https://www.youtube.com/watch?v=B3bAcU2-TGI) by Kit Langton (March 2021) — In this presentation, Kit Langton demonstrated a significant simplification of the service pattern that makes it familiar to OOP programmers, & showed how ZIO 2.0 will auto-wire ZLayers & guide users with rich & actionable error messages.

## ZIO Schedule
- [ZIO Schedule: Conquering Flakiness and Recurrence with Pure Functional Programming](https://www.youtube.com/watch?v=onQSHiafAY8&t=1s) by John De Goes (October 2018)

## ZIO Concurrency Primitives
- [Atomically: Delete Your Actors](https://www.youtube.com/watch?v=d6WWmia0BPM) by John De Goes and Wiem Zine El Abidine (April 2019)
- [ZIO Queue](https://www.youtube.com/watch?v=lBYkLc-j7Vo) by Wiem Zine El Abidine (January 2019)
- [ZIO Queue: A new Queue for a new Era](https://www.youtube.com/watch?v=8JLprl34xEw&t=2437s) by John De Goes (September 2018)
- [ZIO WORLD - Hub](https://www.youtube.com/watch?v=v7Ontn7jZt8) by Adam Fraser (March 2020) — In this presentation, Adam Fraser introduced a hyper-fast async concurrent hub and showed how ZIO Queue and ZIO Hub are radically faster than alternatives.

## ZIO STM
- [Declarative Concurrency with ZIO STM](https://www.youtube.com/watch?v=MEH7hQmGK5M) by Dejan Mijic (June 2020)

## ZIO Test and Debugging
- [0 to 100 with ZIO Test](https://www.youtube.com/watch?v=qDFfVinjDPQ) by Adam Fraser
- [ZIO WORLD - Execution Tracing](https://www.youtube.com/watch?v=1-z06KIde0k) by Rob Walsh (March 2021) — Rob Walsh then presented his work taking ZIO's execution tracing to the next level, which will improve the quality of tracing, as well as make it virtually "cost-free", which is effectively the same as more than doubling the perf of ZIO applications today.
- [Using Aspects To Transform Your Code With ZIO Environment](https://www.youtube.com/watch?v=gcqWdNwNEPg) by Adam Fraser (September 2020)
- [Zymposium — Smart Assertions](https://www.youtube.com/watch?v=lgCb4-4M-fw) by Adam Fraser and Kit Langton (August 2021) — This week, Adam and Kit will demo and explore the inner workings of zio-test's new Smart Assertions. This new test syntax allows you to write simple, straightforward, boolean expressions—all without losing the human-readable, debuggable error messages from other assertion DSLs. As we look at the implementation, we'll discuss the delicate fusion of principled combinator DSLs with hacky macros.

## Migration and Interoperability
- [Functional Legacy - How to Incorporate ZIO In Your Legacy Services](https://www.youtube.com/watch?v=pdgr9bbFQLE) by Natan Silnitsky (March 2020) — You want to introduce ZIO to your existing Scala codebase? Great Idea! It will make your code more efficient, readable, composable, and safe. For the past year, Natan Silnitsky has done this at Wix and has learned a lot about how to do it right. In this talk, Natan will show you how to successfully use ZIO in your legacy service using real-life code examples. You will learn key tips and takeaways including how and when to execute the ZIO runtime; how/when to introduce ZLayers into your codebase; how to make your existing APIs interop with ZIO; and how to have more flexibility on ZManaged resource shutdown. When you're done attending the presentation, you'll be able to introduce ZIO into your existing Scala code base with confidence!

## ZIO Streams
- [Modern Data Driven Applications with ZIO Streams](https://youtu.be/bbss7elSfxs) by Itamar Ravid (December 2019)
- [ZIO Stream: Rebirth](https://www.youtube.com/watch?v=mLJYODobz44&t=15s) by John De Goes and Itamar Ravid (November 2018)
- [The Joys of (Z)Streams](https://www.youtube.com/watch?v=XIIX2YSg7M0) by Itamar Ravid
- [A Tour of ZIO Streams](https://www.youtube.com/watch?v=OiuKbpMOKsc&t=291s) by Itamar Ravid (June 2020) — Watch this video for a brief tour of ZIO Streams from its author, Itamar Ravid. ZIO Streams is a Scala library for creating concurrent, asynchronous stream processing applications, based on the cutting edge functional effect system ZIO.
- [ZIO WORLD - ZIO Streams](https://www.youtube.com/watch?v=8b3t65tmMkE) by Itamar Ravid (March 2020) — In this presentation, Itamar Ravid introduces a radical new basis for ZIO 2.0 Streams, inspired by the work of Michael Snoyman, which unifies streams and sinks & offers next-level performance for streaming apps on the JVM.

## ZIO Libraries
- [What Happens In ZIO Stays in ZIO](https://www.youtube.com/watch?v=gZZazYy0tWM) by Willem Vermeer — ZIO has excellent interoperability with http4s, Cats, Akka libraries, and more, and there are a number of posts and videos that help developers use ZIO with these great libraries. Yet, the question remains: can you develop a web service using ZIO libraries alone? In this talk, we'll explore the answer to this question directly. We'll start by creating an SMTP server using ZIO NIO, and then we'll add a frontend based on ZIO gRPC and Scala.js. Along the way, we'll leverage ZIO Config to externalize the configuration, as well as the ZLayer mechanism for dependency injection. Maybe we'll throw ZIO JSON into the mix, and at the end, we'll take a step back to appreciate how much is really possible gluing those ZIO components together! Finally, we'll conclude by discussing what's still left to be done to make ZIO a full-fledged competitor in the area of full-stack web service development

### ZIO CLI
- [10 Minute Command-Line Apps With ZIO CLI](https://www.youtube.com/watch?v=UeR8YUN4Tws) by Aiswarya Prakasan (December 2020) — Command-line applications are pervasive, relied upon by developers,third-parties, and IT staff. While it’s easy to develop a basic command-line application, building one that features rich help, forgiving parsing, bulletproof validation, shell completions, and a clean and beautiful appearance is quite challenging. In this talk, Aiswarya Prakasan, a contributor to ZIO CLI, will lead you on a journey to building powerful command-line applications in Scala. Through the power of composition and strong types, you will discover how to build type-safe command-line applications in just a few simple lines of code, which give you rich features of command-line applications like git.

### ZIO Flow
- [ZIO WORLD - ZIO FLOW](https://www.youtube.com/watch?v=H4pMkTAsg48) by Aiswarya Prakasan (March 2020) - Aiswarya Prakasan introducing ZIO Flow the new open-source platform will help developers orchestrate microservices for mission-critical code. With a type-safe and compositional design, ZIO Flow will make it easier for developers to create and test robust workflows, which statefully interact with microservices, databases, and human agents, and which survive flakiness, software updates, and even data center failures.
- [Zymposium - ZIO Flow](https://www.youtube.com/watch?v=DDZ8HgWOpBk) (August 2021) — This Friday Adam and Kit will be learning about ZIO Flow, a project that aims to do for distributed programming what ZIO has done for concurrent programming, with special guest Ash. Ash will explain how ZIO Flow lets us execute persistent, distributed, fault-tolerant computations. Then we'll be live coding building a small application in ZIO Flow with her. 

### ZIO SQL
- [ZIO WORLD - ZIO SQL](https://www.youtube.com/watch?v=cIMA6iT9B-k) by Jakub Czuchnowski (March 2020) — Jakub Czuchnowski summarized the latest work in ZIO SQL, demonstrating type-inferred, type-safe queries with the full-range of SQL features, including database-specific functions, with working SELECT/DELETE/UPDATE for Postgres.

### ZIO Web
- [ZIO WORLD - ZIO WEB](https://www.youtube.com/watch?v=UBT-7h8JgU4) by Piotr Golebiewski (March 2020) — Piotr Golebiewski toured ZIO Web, reaching the milestone of "hello world", with working server and client generation for the HTTP protocol.

## Use Cases
- [Search Hacker News with ZIO with Scala, ZIO, Sttp and magic](https://www.youtube.com/watch?v=3P2Gi--dG9A&list=PL-G8WBFTPSVpCcFq6O7czfx9m9T21Cz24&index=11) — A practical look at building a ZIO program. + [Source Code](https://github.com/justinhj/magic-rate-limiter) by [Justin Heyes-Jones](https://twitter.com/justinhj) (April 2021)
- [ML Powered Apps with ZIO Scala](https://www.youtube.com/watch?v=nbZCfkGuNIE) by Aris Vlasakakis (August 2020)
- [Production-grade Microservices with ZIO](https://www.youtube.com/watch?v=oMJ1RMdR7wg) by Itamar Ravid (April 2021) — These days, there are all sorts of boxes to check when deploying production-grade microservices. Our applications must be (reasonably!) performant and correct, but they must also be observable, resilient, and easily extensible. In this talk, Itamar will share from his experience in running microservices based on ZIO in production: resilient, Kubernetes-friendly structuring; cloud-native observability with logs, metrics and telemetry, and modern ways of service communication.

## Others
- [Redis Streams with ZIO](https://youtu.be/jJnco6sMZQY) by [Leszek Gruchała](https://twitter.com/leszekgruchala) (October 2020)
- [The Rise Of Loom And The Evolution Of Reactive Programming](https://www.youtube.com/watch?v=SJeAb-XEIe8&t=2938s) by John A. De Goes (October 2020)
- [Izumi 1.0: Your Next Scala Stack](https://www.youtube.com/watch?v=o65sKWnFyk0) by Pavel Shirshov and Kai (December 2020) — Frameworks are bulky, quirky, and non-compositional, which has led to a rejection of Spring and similar frameworks in the Scala ecosystem. Yet, despite their drawbacks, frameworks have been used to boost team productivity in many large companies. In this presentation, Paul and Kai will introduce Izumi 1.0, a Scala microframework based on compositional functional programming. Designed to help you and your team achieve new levels of productivity, Izumi now includes full compile-time checks for your configurable applications and completely reworked Tagless Final hierarchy for Bifunctors and Trifunctors.
- [ZIO WORLD - Distage 1.0](https://www.youtube.com/watch?v=WJvno8yZuWU) (March 2020) — Distage 1.0, brings the power of tagless-final to trifunctor/bifunctor effect types, with a robust commercial-ready DI framework capable of working with TF & ZLayers w/compile-time validation.
- [Demystifying Functional Effect Systems, Or Build Your Own (Toy) ZIO](https://www.youtube.com/watch?v=Q4OCmKRPUf8) by Dmitry Karlinsky (December 2020) — Ever wondered how ZIO and other functional effect systems work under the hood? Sure feels like magic, sometimes! Dmitry Karlinsky is a big believer in learning by doing, and in this talk, the speaker will walk you through building a toy Scala effect system (called 'TIO'), from scratch, starting with a basic IO monad, and gradually adding capabilities such as Failure and Recovery, Asynchronous Effects, Stack Safety, and finally Concurrency with Fibers. Peek behind the curtain and demystify the technology that is becoming the backbone of modern Scala applications!
