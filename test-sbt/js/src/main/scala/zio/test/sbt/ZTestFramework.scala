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

package zio.test.sbt

import sbt.testing._

final class ZTestFramework extends Framework {
  override final val name: String = s"${Console.UNDERLINED}ZIO Test${Console.RESET}"

  val fingerprints: Array[Fingerprint] = Array(ZioSpecFingerprint)

  override def runner(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader): Runner =
    new ZMasterTestRunnerJS(args, remoteArgs, testClassLoader)

  override def slaveRunner(
    args: Array[String],
    remoteArgs: Array[String],
    testClassLoader: ClassLoader,
    send: String => Unit
  ): Runner =
    new ZSlaveTestRunnerJS(
      args,
      remoteArgs,
      testClassLoader,
      SendSummary.fromSend(summary => send(SummaryProtocol.serialize(summary)))
    )

}
