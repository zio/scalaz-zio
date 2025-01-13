/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test

import zio.Clock.ClockLive
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.render.ConsoleRenderer
import zio._

import java.util.concurrent.TimeUnit

/**
 * A `TestExecutor[R, E]` is capable of executing specs that require an
 * environment `R` and may fail with an `E`.
 */
abstract class TestExecutor[+R, E] {
  def run(fullyQualifiedName: String, spec: Spec[R, E], defExec: ExecutionStrategy)(implicit trace: Trace): UIO[Summary]
}

object TestExecutor {
  private[this] val exitRightUnit = Exit.succeed(Right(()))
  private[this] val exitLeftUnit  = Exit.succeed(Left(()))

  // Used to override the default shutdown for a suite so we don't have to wait 60 seconds in some tests.
  // Might be useful to make public at some point
  private[zio] val overrideShutdownTimeout: FiberRef[Option[Duration]] =
    FiberRef.unsafe.make[Option[Duration]](None)(Unsafe)

  def default[R, E](
    sharedSpecLayer: ZLayer[Any, E, R],
    freshLayerPerSpec: ZLayer[Any, Nothing, TestEnvironment with Scope],
    sinkLayer: Layer[Nothing, ExecutionEventSink],
    eventHandlerZ: ZTestEventHandler
  ): TestExecutor[R with TestEnvironment with Scope, E] =
    new TestExecutor[R with TestEnvironment with Scope, E] {
      def run(fullyQualifiedName: String, spec: Spec[R with TestEnvironment with Scope, E], defExec: ExecutionStrategy)(
        implicit trace: Trace
      ): UIO[Summary] =
        (for {
          sink     <- ZIO.service[ExecutionEventSink]
          topParent = SuiteId.global
          _ <- {
            def processEvent(event: ExecutionEvent) =
              sink.process(event) *> eventHandlerZ.handle(event)

            def loop(
              labels: List[String],
              spec: Spec[Scope, E],
              exec: ExecutionStrategy,
              ancestors: List[SuiteId],
              sectionId: SuiteId
            ): ZIO[Scope, Nothing, Unit] =
              spec.caseValue match {
                case Spec.ExecCase(exec, spec) =>
                  loop(labels, spec, exec, ancestors, sectionId)

                case Spec.LabeledCase(label, spec) =>
                  loop(label :: labels, spec, exec, ancestors, sectionId)

                case Spec.ScopedCase(managed) =>
                  val spec = managed.flatMap(loop(labels, _, exec, ancestors, sectionId))
                  Scope.make.flatMap { scope =>
                    scope
                      .extend(spec)
                      .onExit { exit =>
                        implicit val unsafe: Unsafe = Unsafe
                        val latch                   = Promise.unsafe.make[Nothing, Either[Unit, Unit]](FiberId.None)
                        for {
                          timeoutOpt <- overrideShutdownTimeout.get
                          cancelWarning = {
                            val timeout = timeoutOpt.getOrElse(60.seconds)
                            Clock.globalScheduler.schedule(
                              () => {
                                Runtime.default.unsafe.run(
                                  ZIO
                                    .logWarning({
                                      "Warning: ZIO Test is attempting to close the scope of suite " +
                                        s"${labels.reverse.mkString(" - ")} in $fullyQualifiedName, " +
                                        s"but closing the scope has taken more than ${timeout.toSeconds} seconds to " +
                                        "complete. This may indicate a resource leak."
                                    })
                                )
                                latch.unsafe.done(exitLeftUnit)
                              },
                              timeout
                            )
                          }
                          finalizer <- scope
                                         .close(exit)
                                         .ensuring(ZIO.succeed {
                                           latch.unsafe.done(exitRightUnit)
                                           cancelWarning(): Unit
                                         })
                                         .forkDaemon
                          exit <- latch.await
                          _    <- finalizer.join.whenDiscard(exit.isRight)
                        } yield ()
                      }
                  }.catchAllCause { e =>
                    val event = ExecutionEvent.RuntimeFailure(sectionId, labels, TestFailure.Runtime(e), ancestors)
                    processEvent(event)
                  }

                case Spec.MultipleCase(specs) =>
                  ZIO.uninterruptibleMask(restore =>
                    SuiteId.newRandom.flatMap { newMultiSectionId =>
                      val newAncestors = sectionId :: ancestors
                      val start        = ExecutionEvent.SectionStart(labels, newMultiSectionId, newAncestors)
                      val end          = ExecutionEvent.SectionEnd(labels, newMultiSectionId, newAncestors)
                      val specs0       = specs.map(loop(labels, _, exec, newAncestors, newMultiSectionId))
                      val run          = ZIO.foreachExec(specs0)(exec)(ZIO.identityFn)
                      processEvent(start) *> restore(run).exitWith(exit => processEvent(end) *> exit.unitExit)
                    }
                  )
                case Spec.TestCase(
                      test,
                      staticAnnotations: TestAnnotationMap
                    ) =>
                  val testResultZ = (for {
                    _ <-
                      processEvent(
                        ExecutionEvent.TestStarted(
                          labels,
                          staticAnnotations,
                          ancestors,
                          sectionId,
                          fullyQualifiedName
                        )
                      )
                    start  <- ClockLive.currentTime(TimeUnit.MILLISECONDS)
                    result <- Live.live(test).either
                    end    <- ClockLive.currentTime(TimeUnit.MILLISECONDS)
                  } yield {
                    ExecutionEvent
                      .Test(
                        labels,
                        result,
                        staticAnnotations ++ extractAnnotations(result),
                        ancestors,
                        end - start,
                        sectionId,
                        fullyQualifiedName
                      )
                  }).catchAllCause { e =>
                    val event = ExecutionEvent.RuntimeFailure(sectionId, labels, TestFailure.Runtime(e), ancestors)
                    ConsoleRenderer.render(e, labels).foreach(cr => println("CR: " + cr))
                    Exit.succeed(event)
                  }
                  testResultZ.flatMap(processEvent)
              }

            val scopedSpec =
              (spec @@ TestAspect.aroundTest(
                ZTestLogger.default.build.as(ZIO.successFn[TestSuccess])
              )).annotated
                .provideSomeLayer[R](freshLayerPerSpec)
                .provideLayerShared(sharedSpecLayer.tapErrorCause { e =>
                  processEvent(
                    ExecutionEvent.RuntimeFailure(
                      SuiteId(-1),
                      List("Top level layer construction failure. No tests will execute."),
                      TestFailure.Runtime(e),
                      List.empty
                    )
                  )
                })

            val topLevelFlush = ExecutionEvent.TopLevelFlush(topParent)

            ZIO.scoped(loop(List.empty, scopedSpec, defExec, List.empty, topParent)) *>
              processEvent(topLevelFlush) *>
              TestDebug.deleteIfEmpty(fullyQualifiedName)

          }
          summary <- sink.getSummary
        } yield summary).provideLayer(sinkLayer)

      private def extractAnnotations(result: Either[TestFailure[E], TestSuccess]) =
        result match {
          case Left(testFailure)  => testFailure.annotations
          case Right(testSuccess) => testSuccess.annotations
        }

    }
}
