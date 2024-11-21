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

import scala.annotation.nowarn
import scala.reflect.macros.whitebox

/**
 * Generates method accessors for a service into annotated object.
 */
private[macros] class AccessibleMacro(override val c: whitebox.Context) extends AccessibleMacroBase(c) {

  import c.universe._

  protected val macroName: String = "accessible"

  @nowarn("msg=pattern var [^\\s]+ in method unapply is never used")
  override def macroApply(annottees: Seq[c.Tree]): MacroApply = new MacroApply(annottees) {
    protected def treeTpe(tree: Tree): Type =
      (tree: @unchecked) match {
        case tq"$typeName[..$typeParams]" =>
          val typeArgs = typeParams.map(t => c.typecheck(tq"$t", c.TYPEmode, silent = true).tpe)
          c.typecheck(tq"$typeName[..$typeArgs]", c.TYPEmode).tpe
      }

    override protected def typeArgsForService(serviceTypeParams: List[TypeDef]): List[TypeName] =
      serviceTypeParams.map(_.name)

    override protected def typeParamsForAccessors(serviceTypeParams: List[TypeDef]): List[TypeDef] =
      serviceTypeParams
  }

}
