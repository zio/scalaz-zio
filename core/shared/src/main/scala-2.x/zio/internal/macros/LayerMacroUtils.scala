package zio.internal.macros

import zio._
import zio.internal.ansi.AnsiStringOps

import scala.reflect.macros.blackbox

private[zio] trait LayerMacroUtils {
  val c: blackbox.Context
  import c.universe._

  type LayerExpr = c.Expr[ZLayer[_, _, _]]

  def generateExprGraph(layers: Seq[LayerExpr]): ZLayerExprBuilder[c.Type, LayerExpr] =
    generateExprGraph(layers.map(getNode).toList)

  def generateExprGraph(nodes: List[Node[c.Type, LayerExpr]]): ZLayerExprBuilder[c.Type, LayerExpr] =
    ZLayerExprBuilder[c.Type, LayerExpr](
      graph = Graph(
        nodes = nodes,
        // They must be `.toString`-ed as a backup in the case of refinement
        // types. Otherwise, [[examples.DumbExample]] will fail.
        keyEquals = (t1, t2) => t1 =:= t2 || (t1.toString == t2.toString)
      ),
      showKey = tpe => tpe.toString,
      showExpr = expr => CleanCodePrinter.show(c)(expr.tree),
      abort = c.abort(c.enclosingPosition, _),
      emptyExpr = reify(ZLayer.succeed(())),
      composeH = (lhs, rhs) => c.Expr(q"""$lhs ++ $rhs"""),
      composeV = (lhs, rhs) => c.Expr(q"""$lhs >>> $rhs""")
    )

  def buildMemoizedLayer(exprGraph: ZLayerExprBuilder[c.Type, LayerExpr], requirements: List[c.Type]): LayerExpr = {
    // This is run for its side effects: Reporting compile errors with the original source names.
    val _ = exprGraph.buildLayerFor(requirements)

    val nodes = exprGraph.graph.nodes
    val memoizedNodes = nodes.map { node =>
      val freshName = c.freshName("layer")
      val termName  = TermName(freshName)
      node.copy(value = c.Expr[ZLayer[_, _, _]](q"$termName"))
    }

    val definitions = memoizedNodes.zip(nodes).map { case (memoizedNode, node) =>
      ValDef(Modifiers(), TermName(memoizedNode.value.tree.toString()), TypeTree(), node.value.tree)
    }
    val layerExpr = exprGraph
      .copy(graph = Graph[c.Type, LayerExpr](memoizedNodes, exprGraph.graph.keyEquals))
      .buildLayerFor(requirements)

    c.Expr(q"""
    ..$definitions
    ${layerExpr.tree}
    """)
  }

  def getNode(layer: LayerExpr): Node[c.Type, LayerExpr] = {
    val typeArgs = layer.actualType.dealias.typeArgs
    // ZIO[in, _, out]
    val in  = typeArgs.head
    val out = typeArgs(2)
    Node(getRequirements(in), getRequirements(out), layer)
  }

  def getRequirements[T: c.WeakTypeTag]: List[c.Type] =
    getRequirements(weakTypeOf[T])

  def isValidHasType(tpe: Type): Boolean =
    tpe.isHas || tpe.isAny

  def injectBaseImpl[F[_, _, _], R: c.WeakTypeTag, E, A](
    layers: Seq[c.Expr[ZLayer[_, E, _]]],
    method: String
  ): c.Expr[F[Any, E, A]] = {
    assertProperVarArgs(layers)
    val expr = buildMemoizedLayer(generateExprGraph(layers), getRequirements[R])
    c.Expr[F[Any, E, A]](q"${c.prefix}.${TermName(method)}(${expr.tree})")
  }

  def injectSomeBaseImpl[F[_, _, _], R0: c.WeakTypeTag, R: c.WeakTypeTag, E, A](
    layers: Seq[c.Expr[ZLayer[_, E, _]]],
    method: String
  ): c.Expr[F[R0, E, A]] = {
    assertProperVarArgs(layers)
    val remainderRequirements = getRequirements[R0]
    val remainderExpr =
      if (weakTypeOf[R0] =:= weakTypeOf[ZEnv]) reify(ZEnv.any)
      else reify(ZLayer.environment[R0])

    val remainderNode = Node(List.empty, remainderRequirements, remainderExpr)
    val nodes         = (remainderNode +: layers.map(getNode)).toList

    val layerExpr = buildMemoizedLayer(generateExprGraph(nodes), getRequirements[R])
    c.Expr[F[R0, E, A]](q"${c.prefix}.${TermName(method)}(${layerExpr.tree})")
  }

  def getRequirements(tpe: Type): List[c.Type] = {
    val intersectionTypes = tpe.dealias.map(_.dealias).intersectionTypes

    intersectionTypes.filter(!isValidHasType(_)) match {
      case Nil => ()
      case nonHasTypes =>
        c.abort(
          c.enclosingPosition,
          s"\nContains non-Has types:\n- ${nonHasTypes.map(_.toString.yellow).mkString("\n- ")}"
        )
    }

    intersectionTypes
      .filter(_.isHas)
      .map(_.dealias.typeArgs.head.dealias)
      .distinct
  }

  def assertProperVarArgs(layers: Seq[c.Expr[_]]): Unit = {
    val _ = layers.map(_.tree) collect { case Typed(_, Ident(typeNames.WILDCARD_STAR)) =>
      c.abort(
        c.enclosingPosition,
        "Auto-construction cannot work with `someList: _*` syntax.\nPlease pass the layers themselves into this method."
      )
    }
  }

  implicit class TypeOps(self: Type) {
    def isHas: Boolean = self.dealias.typeSymbol == typeOf[Has[_]].typeSymbol

    def isAny: Boolean = self.dealias.typeSymbol == typeOf[Any].typeSymbol

    /**
     * Given a type `A with B with C` You'll get back List[A,B,C]
     */
    def intersectionTypes: List[Type] =
      self.dealias match {
        case t: RefinedType =>
          t.parents.flatMap(_.intersectionTypes)
        case TypeRef(_, sym, _) if sym.info.isInstanceOf[RefinedTypeApi] =>
          sym.info.intersectionTypes
        case other =>
          List(other)
      }
  }

  implicit class TreeOps(self: c.Expr[_]) {
    def showTree: String = CleanCodePrinter.show(c)(self.tree)
  }
}

trait ExprGraphCompileVariants {}
