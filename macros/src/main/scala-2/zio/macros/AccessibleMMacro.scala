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

package zio.macros

import scala.reflect.macros.whitebox

/**
 * Generates method accessors for a service parametrized by HKT into annotated
 * object
 */
private[macros] class AccessibleMMacro(override val c: whitebox.Context) extends AccessibleMMacroBase(c) {

  import c.universe._

  private lazy val task: Tree = tq"_root_.zio.Task"
  private lazy val uio: Tree  = tq"_root_.zio.UIO"

  private lazy val taskManaged: Tree = tq"_root_.zio.managed.TaskManaged"
  private lazy val uManaged: Tree    = tq"_root_.zio.managed.UManaged"

  protected lazy val macroName: String = "accessibleM"

  protected lazy val aliases: Seq[Tree] = Seq(task, uio, taskManaged, uManaged)

  protected def expectedTypeParams: Long = 1

}
