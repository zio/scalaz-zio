/*
 * Copyright 2024-2024 Vincent Raman and the ZIO Contributors
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

package zio.test.junit

import org.junit.platform.engine.{
  EngineDiscoveryRequest,
  ExecutionRequest,
  TestDescriptor,
  TestEngine,
  TestExecutionResult,
  UniqueId
}
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolver
import zio._

import scala.jdk.CollectionConverters._

/**
 * A JUnit platform test engine implementation designed for running ZIO tests.
 */
class ZIOTestEngine extends TestEngine {

  override def getId: String = "zio"

  private lazy val discoverer = EngineDiscoveryRequestResolver
    .builder[EngineDescriptor]()
    .addSelectorResolver(new ZIOTestClassSelectorResolver)
    .build()

  override def discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor = {
    val engineDesc = new EngineDescriptor(uniqueId, "ZIO EngineDescriptor")
    discoverer.resolve(discoveryRequest, engineDesc)
    engineDesc
  }

  override def execute(request: ExecutionRequest): Unit =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run {
        val engineDesc = request.getRootTestDescriptor
        val listener   = request.getEngineExecutionListener
        ZIO.logInfo("Start tests execution...") *> ZIO.foreachDiscard(engineDesc.getChildren.asScala) {
          case clzDesc: ZIOTestClassDescriptor =>
            ZIO.logInfo(s"Start execution of test class ${clzDesc.className}...") *> ZIO.succeed(
              listener.executionStarted(clzDesc)
            ) *> new ZIOTestClassRunner(clzDesc).run(listener) *> ZIO.succeed(
              listener.executionFinished(clzDesc, TestExecutionResult.successful())
            )
          case otherDesc =>
            // Do nothing for other descriptor, just log it.
            ZIO.logWarning(s"Found test descriptor $otherDesc that is not supported, skipping.")

        } *> ZIO.succeed(listener.executionFinished(engineDesc, TestExecutionResult.successful())) *> ZIO.logInfo(
          "Completed tests execution."
        )
      }
        .getOrThrowFiberFailure()
    }
}
