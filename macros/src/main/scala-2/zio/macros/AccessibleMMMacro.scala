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
private[macros] class AccessibleMMMacro(override val c: whitebox.Context) extends AccessibleMMacroBase(c) {

  import c.universe._

  private lazy val io: Tree   = tq"_root_.zio.IO"
  private lazy val rio: Tree  = tq"_root_.zio.RIO"
  private lazy val urio: Tree = tq"_root_.zio.URIO"

  private lazy val managed: Tree   = tq"_root_.zio.managed.Managed"
  private lazy val rManaged: Tree  = tq"_root_.zio.managed.RManaged"
  private lazy val urManaged: Tree = tq"_root_.zio.managed.URManaged"

  protected lazy val macroName: String = "accessibleMM"

  protected lazy val aliases: Seq[Tree] = Seq(io, rio, urio, managed, rManaged, urManaged)

  protected def expectedTypeParams: Long = 2
}
