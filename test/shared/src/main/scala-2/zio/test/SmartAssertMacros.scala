package zio.test

import zio.{Cause, Exit}

import scala.annotation.{nowarn, tailrec}
import scala.reflect.macros.blackbox

class SmartAssertMacros(val c: blackbox.Context) {
  import c.universe._

  private val SA         = q"_root_.zio.test.internal.SmartAssertions"
  private val Arrow      = q"_root_.zio.test.TestArrow"
  private val TestResult = q"_root_.zio.test.TestResult"

  def assert_impl(expr: c.Expr[Boolean], exprs: c.Expr[Boolean]*): c.Tree =
    exprs.map(assertOne_impl).foldLeft(assertOne_impl(expr)) { (acc, assert) =>
      q"$acc && $assert"
    }

  sealed trait AST { self =>
    def span: (Int, Int)
    def withSpan(span0: (Int, Int)): AST =
      self match {
        case not: AST.Not           => not.copy(span = span0)
        case and: AST.And           => and.copy(span = span0)
        case or: AST.Or             => or.copy(span = span0)
        case method: AST.Method     => method.copy(span = span0)
        case function: AST.Function => function.copy(span = span0)
        case raw: AST.Raw           => raw.copy(span = span0)
      }
  }

  object AST {
    case class Not(ast: AST, span: (Int, Int), innerSpan: (Int, Int))                                 extends AST
    case class And(lhs: AST, rhs: AST, span: (Int, Int), leftSpan: (Int, Int), rightSpan: (Int, Int)) extends AST
    case class Or(lhs: AST, rhs: AST, span: (Int, Int), leftSpan: (Int, Int), rightSpan: (Int, Int))  extends AST
    case class Method(
      lhs: AST,
      lhsTpe: Type,
      rhsTpe: Type,
      name: String,
      tpes: List[Type],
      args: Option[List[c.Tree]],
      span: (Int, Int)
    ) extends AST
    case class Function(lhs: c.Tree, rhs: AST, lhsTpe: Type, span: (Int, Int)) extends AST
    case class Raw(ast: c.Tree, span: (Int, Int))                              extends AST
  }

  case class AssertAST(
    name: String,
    tpes: List[Type] = List.empty,
    args: List[c.Tree] = List.empty,
    implicits: Boolean = false
  ) {
    def this(name: String, tpes: List[Type], args: List[c.Tree]) =
      this(name, tpes, args, false)
    def copy(name: String = name, tpes: List[Type] = tpes, args: List[c.Tree] = args): AssertAST =
      AssertAST(name, tpes, args, implicits)
  }

  object AssertAST {
    def apply(name: String, tpes: List[Type], args: List[c.Tree]): AssertAST = AssertAST(name, tpes, args, false)

    def toTree(assertAST: AssertAST): c.Tree = {
      val implicits = q"import zio.test.internal.SmartAssertions.Implicits._"
      if (assertAST.implicits)
        assertAST match {
          case AssertAST(name, List(), List(), _) =>
            q"""{$implicits
            $SA.${TermName(name)}}"""
          case AssertAST(name, List(), args, _) =>
            q"""{$implicits
            $SA.${TermName(name)}(..$args)}"""
          case AssertAST(name, tpes, List(), _) =>
            q"""{$implicits
            $SA.${TermName(name)}[..$tpes]}"""
          case AssertAST(name, tpes, args, _) =>
            q"""{$implicits
            $SA.${TermName(name)}[..$tpes](..$args)}"""
        }
      else
        assertAST match {
          case AssertAST(name, List(), List(), _) =>
            q"$SA.${TermName(name)}"
          case AssertAST(name, List(), args, _) =>
            q"$SA.${TermName(name)}(..$args)"
          case AssertAST(name, tpes, List(), _) =>
            q"$SA.${TermName(name)}[..$tpes]"
          case AssertAST(name, tpes, args, _) =>
            q"$SA.${TermName(name)}[..$tpes](..$args)"
        }
    }
  }

  def parseAsAssertion(ast: AST)(start: c.Tree)(implicit positionContext: PositionContext): c.Tree =
    ast match {
      case AST.Method(lhs, _, _, "some", _, _, span) =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.isSome.span($span)"

      case AST.Method(lhs, _, _, "right", _, _, span) =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asRight.span($span)"

      case AST.Method(lhs, _, _, "left", _, _, span) =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asLeft.span($span)"

      case AST.Method(lhs, _, _, "anything", _, _, span) =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.anything.span($span)"

      case AST.Method(lhs, lhsTpe, _, "subtype", List(tpe), _, span) =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.as[${lhsTpe.typeArgs.head}, $tpe].span($span)"

      case AST.Method(lhs, _, _, "custom", List(_), Some(List(customAssertion)), span) =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.custom($customAssertion).span($span)"

      case AST.Method(lhs, lhsTpe, _, "die", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[Exit[_, _]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asExitDie.span($span)"

      case AST.Method(lhs, lhsTpe, _, "failure", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[Exit[_, _]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asExitFailure.span($span)"

      case AST.Method(lhs, lhsTpe, _, "success", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[Exit[_, _]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asExitSuccess.span($span)"

      case AST.Method(lhs, lhsTpe, _, "interrupted", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[Exit[_, _]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asExitInterrupted.span($span)"

      case AST.Method(lhs, lhsTpe, _, "success", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[scala.util.Try[_]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asTrySuccess.span($span)"

      case AST.Method(lhs, lhsTpe, _, "failure", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[scala.util.Try[_]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asTryFailure.span($span)"

      case AST.Method(lhs, lhsTpe, _, "die", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[Cause[_]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asCauseDie.span($span)"

      case AST.Method(lhs, lhsTpe, _, "failure", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[Cause[_]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asCauseFailure.span($span)"

      case AST.Method(lhs, lhsTpe, _, "interrupted", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[Cause[_]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asCauseInterrupted.span($span)"

      case AST.Method(lhs, lhsTpe, _, "cause", _, _, span) if lhsTpe <:< weakTypeOf[TestLens[Exit[_, _]]] =>
        q"${parseAsAssertion(lhs)(start)} >>> $SA.asExitCause.span($span)"

      case _ =>
        start
    }

  def astToAssertion(ast: AST)(implicit positionContext: PositionContext): c.Tree =
    ast match {
      case AST.Not(ast, _, _) =>
        q"!${astToAssertion(ast)}"

      case AST.And(lhs, rhs, _, ls, rs) =>
        q"${astToAssertion(lhs)}.withParentSpan($ls) && ${astToAssertion(rhs)}.withParentSpan($rs)"

      case AST.Or(lhs, rhs, _, ls, rs) =>
        q"${astToAssertion(lhs)}.withParentSpan($ls) || ${astToAssertion(rhs)}.withParentSpan($rs)"

      // Matches `zio.test.SmartAssertionOps.as`
      case AST.Method(lhs, _, _, "is", _, Some(List(arg)), _) if arg.tpe.typeArgs.head <:< weakTypeOf[TestLens[_]] =>
        val assertion = astToAssertion(lhs)
        parseExpr(arg) match {
          case AST.Function(_, rhs, _, _) => parseAsAssertion(rhs)(assertion)
          case _                          => throw new Error("This is not possible.")
        }

      case AST.Method(lhs, lhsTpe, _, "forall", _, Some(args), span) if lhsTpe <:< weakTypeOf[Iterable[_]] =>
        val assertion = astToAssertion(parseExpr(args.head))
        q"${astToAssertion(lhs)} >>> $SA.forallIterable($assertion).span($span)"

      case AST.Method(lhs, lhsTpe, _, "exists", _, Some(args), span) if lhsTpe <:< weakTypeOf[Iterable[_]] =>
        val assertion = astToAssertion(parseExpr(args.head))
        q"${astToAssertion(lhs)} >>> $SA.existsIterable($assertion).span($span)"

      case Matcher(lhs, ast, span) =>
        val tree = AssertAST.toTree(ast)
        q"${astToAssertion(lhs)} >>> $tree.span($span)"

      case AST.Method(lhs, lhsTpe, _, name, tpes, args, span) =>
        val select =
          args match {
            case Some(args) =>
              c.untypecheck(q"{ (a: $lhsTpe) => a.${TermName(name)}[..$tpes](..$args) }")
            case None =>
              c.untypecheck(q"{ (a: $lhsTpe) => a.${TermName(name)}[..$tpes] }")
          }

        q"${astToAssertion(lhs)} >>> $Arrow.fromFunction($select).span($span)"

      case AST.Function(lhs, rhs, _, span) =>
        val rhsAssert = astToAssertion(rhs)
        val select    = c.untypecheck(q"{ ($lhs) => $rhsAssert }")
        q"$Arrow.suspend($select).span($span)"

      case AST.Raw(ast, span) =>
        q"$Arrow.succeed($ast).span($span)"
    }

  case class PositionContext(start: Int, codeString: String) {
    def getPos(tree: c.Tree): (Int, Int) = (getStart(tree), getEnd(tree))
    def getEnd(tree: c.Tree): Int        = tree.pos.end - start
    def getStart(tree: c.Tree): Int      = tree.pos.start - start
  }

  @nowarn("msg=never used")
  def parseExpr(tree: c.Tree)(implicit pos: PositionContext): AST = {
    val end = pos.getEnd(tree)

    tree match {
      case q"!($inner)" =>
        AST.Not(parseExpr(inner), pos.getPos(tree), pos.getPos(inner))

      case q"$lhs && $rhs" if lhs.tpe == typeOf[Boolean] =>
        AST.And(parseExpr(lhs), parseExpr(rhs), pos.getPos(tree), pos.getPos(lhs), pos.getPos(rhs))

      case q"$lhs || $rhs" if lhs.tpe == typeOf[Boolean] =>
        AST.Or(parseExpr(lhs), parseExpr(rhs), pos.getPos(tree), pos.getPos(lhs), pos.getPos(rhs))

      case MethodCall(lhs, name, tpes, args) =>
        AST.Method(
          parseExpr(lhs),
          lhs.tpe.widen,
          tree.tpe.widen,
          name.toString,
          tpes,
          args,
          (pos.getEnd(lhs), end)
        )

      case fn @ q"($a) => $b" =>
        val inType = fn.tpe.widen.typeArgs.head
        AST.Function(a, parseExpr(b), inType, (pos.getStart(tree), end))

      case _ => AST.Raw(tree, (pos.getStart(tree), end))
    }
  }

  def assertOne_impl(expr: Expr[Boolean]): c.Tree = {
    val (stats, tree) = expr.tree match {
      case Block(stats, expr) => (stats, expr)
      case other              => (Nil, other)
    }

    val (_, start, codeString)        = text(tree)
    implicit val pos: PositionContext = PositionContext(start, codeString)

    val parsed   = parseExpr(tree)
    val ast      = astToAssertion(parsed)
    val location = Some(s"${tree.pos.source.file.path}:${tree.pos.line}")

    q"""
..$stats
$TestResult($ast.withCode($codeString).meta(location = $location))
"""
  }

  object UnwrapImplicit {
    @nowarn("msg=never used")
    def unapply(tree: c.Tree): Option[c.Tree] =
      tree match {
        case q"$wrapper($lhs)" if wrapper.symbol.isImplicit =>
          Some(lhs)
        case _ => Some(tree)
      }
  }

  object MethodCall {
    @nowarn("msg=never used")
    def unapply(tree: c.Tree): Option[(c.Tree, TermName, List[Type], Option[List[c.Tree]])] =
      tree match {
        case q"${UnwrapImplicit(lhs)}.$name[..$tpes]"
            if !(tree.symbol.isModule || tree.symbol.isStatic || tree.symbol.isClass) =>
          Some((lhs, name, tpes.map(_.tpe), None))
        case q"${UnwrapImplicit(lhs)}.$name"
            if !(tree.symbol.isModule || tree.symbol.isStatic || tree.symbol.isClass) =>
          Some((lhs, name, List.empty, None))
        case q"${UnwrapImplicit(lhs)}.$name(..$args)" =>
          Some((lhs, name, List.empty, Some(args)))
        case q"${UnwrapImplicit(lhs)}.$name[..$tpes](..$args)" =>
          Some((lhs, name, tpes.map(_.tpe), Some(args)))
        case _ => None
      }
  }

  object IsConstructor {
    def unapply(tree: c.Tree): Option[c.Tree] =
      tree match {
        case Apply(_, _) | TypeApply(_, _) if isConstructor(tree) => Some(tree)
        case _                                                    => None
      }

    @tailrec
    private def isConstructor(tree: c.Tree): Boolean =
      tree match {
        case Select(Literal(_), _) => false
        case Select(Select(s, _), TermName("apply"))
            if s.symbol.isModule || s.symbol.isSynthetic || s.symbol.isClass || s.symbol.isStatic =>
          true
        case Select(s, _)
            if s != null && (s.symbol.isModule || s.symbol.isSynthetic || s.symbol.isClass || s.symbol.isStatic) =>
          true
        case TypeApply(s, _) => isConstructor(s)
        case Apply(s, _)     => isConstructor(s)
        case _               => false
      }
  }

  // Pilfered (with immense gratitude & minor modifications)
  // from https://github.com/com-lihaoyi/sourcecode
  private def text[T: c.WeakTypeTag](tree: c.Tree): (Int, Int, String) = {
    val fileContent = new String(tree.pos.source.content)
    var start = tree.collect { case treeVal =>
      treeVal.pos match {
        case NoPosition => Int.MaxValue
        case p          => p.start
      }
    }.min
    val initialStart = start

    // Moves to the true beginning of the expression, in the case where the
    // internal expression is wrapped in parens.
    while ((start - 2) >= 0 && fileContent(start - 2) == '(') {
      start -= 1
    }

    val g      = c.asInstanceOf[reflect.macros.runtime.Context].global
    val parser = g.newUnitParser(fileContent.drop(start))
    parser.expr()
    val end = parser.in.lastOffset
    (initialStart - start, start, fileContent.slice(start, start + end))
  }

  trait ASTConverter { self =>
    final def orElse(that: ASTConverter): ASTConverter = new ASTConverter {
      override def unapply(method: AST.Method): Option[(AST, AssertAST, (Int, Int))] =
        self.unapply(method).orElse(that.unapply(method))
    }

    def unapply(method: AST.Method): Option[(AST, AssertAST, (Int, Int))]
  }

  object ASTConverter {
    def make(pf: PartialFunction[AST.Method, AssertAST]): ASTConverter = new ASTConverter {
      override def unapply(method: AST.Method): Option[(AST, AssertAST, (Int, Int))] =
        pf.lift(method).map { result =>
          (method.lhs, result, method.span)
        }
    }
  }

  object Matcher {

    def tpesPriority(tpe: Type): Int =
      tpe.typeSymbol.fullName match {
        case "scala.Byte" | "java.lang.Byte"      => 0
        case "scala.Short" | "java.lang.Short"    => 1
        case "scala.Char" | "java.lang.Character" => 2
        case "scala.Int" | "java.lang.Integer"    => 3
        case "scala.Long" | "java.lang.Long"      => 4
        case "scala.Float" | "java.lang.Float"    => 5
        case "scala.Double" | "java.lang.Double"  => 6
        case _                                    => -1
      }

    // `true` for conversion from `lhs` to `rhs`.
    def implicitConversionDirection(lhs: Type, rhs: Type): Option[Boolean] =
      if (tpesPriority(lhs) == -1 || tpesPriority(rhs) == -1) {
        // tq"lhs => rhs" does not work. It seems to generate untyped tree that cannot be typechecked
        val function1 = weakTypeOf[_ => _]
        c.inferImplicitValue(appliedType(function1, lhs, rhs)) match {
          case EmptyTree =>
            c.inferImplicitValue(appliedType(function1, rhs, lhs)) match {
              case EmptyTree => None
              case _         => Some(false)
            }
          case _ => Some(true)
        }
      } else if (tpesPriority(rhs) - tpesPriority(lhs) > 0) Some(true)
      else Some(false)

    def needsImplicits(lhs: Type, rhs: Type) =
      lhs.typeSymbol.fullName.contains("java.lang") || rhs.typeSymbol.fullName.contains("java.lang")

    def comparisonConverter(lhsTpe: Type, args: List[c.Tree], methodName: String): AssertAST = {
      val rhsTpe = args.head.tpe.widen
      if (lhsTpe =:= rhsTpe)
        AssertAST(methodName, List(lhsTpe), args)
      else
        implicitConversionDirection(lhsTpe, rhsTpe) match {
          case Some(true)  => AssertAST(methodName ++ "L", List(lhsTpe, rhsTpe), args, needsImplicits(lhsTpe, rhsTpe))
          case Some(false) => AssertAST(methodName ++ "R", List(lhsTpe, rhsTpe), args, needsImplicits(lhsTpe, rhsTpe))
          case None        => AssertAST(methodName, List(lhsTpe), args, needsImplicits(lhsTpe, rhsTpe))
        }
    }

    def unapply(method: AST.Method): Option[(AST, AssertAST, (Int, Int))] =
      all.reduce(_ orElse _).unapply(method)

    val asInstanceOf: ASTConverter =
      ASTConverter.make { case AST.Method(_, lhsTpe, _, "asInstanceOf", List(tpe), _, _) =>
        AssertAST("as", List(lhsTpe, tpe))
      }

    val isInstanceOf: ASTConverter =
      ASTConverter.make { case AST.Method(_, lhsTpe, _, "isInstanceOf", List(tpe), _, _) =>
        AssertAST("is", List(lhsTpe, tpe))
      }

    val equalTo: ASTConverter =
      ASTConverter.make { case AST.Method(_, lhsTpe, _, "$eq$eq", _, Some(args), _) =>
        comparisonConverter(lhsTpe, args, "equalTo")
      }

    val get: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "get", _, _, _) if lhsTpe <:< weakTypeOf[Option[_]] =>
          AssertAST("isSome")
      }

    val isEven: ASTConverter =
      new ASTConverter {
        def unapply(method: AST.Method): Option[(AST, AssertAST, (Int, Int))] =
          method match {
            case AST.Method(
                  AST.Method(lhs, lhsTpe, _, "$percent", _, Some(List(q"2")), span0),
                  _,
                  _,
                  "$eq$eq",
                  _,
                  Some(List(q"0")),
                  span
                ) =>
              Some((lhs, AssertAST("isEven", List(lhsTpe)), span0._1 -> span._2))
            case _ => None
          }
      }

    val isOdd: ASTConverter =
      new ASTConverter {
        def unapply(method: AST.Method): Option[(AST, AssertAST, (Int, Int))] =
          method match {
            case AST.Method(
                  AST.Method(lhs, lhsTpe, _, "$percent", _, Some(List(q"2")), span0),
                  _,
                  _,
                  "$eq$eq",
                  _,
                  Some(List(q"1")),
                  span
                ) =>
              Some((lhs, AssertAST("isOdd", List(lhsTpe)), span0._1 -> span._2))
            case _ => None
          }
      }

    val greaterThan: ASTConverter =
      ASTConverter.make { case AST.Method(_, lhsTpe, _, "$greater", _, Some(args), _) =>
        comparisonConverter(lhsTpe, args, "greaterThan")
      }

    val greaterThanOrEqualTo: ASTConverter =
      ASTConverter.make { case AST.Method(_, lhsTpe, _, "$greater$eq", _, Some(args), _) =>
        comparisonConverter(lhsTpe, args, "greaterThanOrEqualTo")
      }

    val lessThan: ASTConverter =
      ASTConverter.make { case AST.Method(_, lhsTpe, _, "$less", _, Some(args), _) =>
        comparisonConverter(lhsTpe, args, "lessThan")
      }

    val lessThanOrEqualTo: ASTConverter =
      ASTConverter.make { case AST.Method(_, lhsTpe, _, "$less$eq", _, Some(args), _) =>
        comparisonConverter(lhsTpe, args, "lessThanOrEqualTo")
      }

    val head: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "head", _, _, _) if lhsTpe <:< weakTypeOf[Iterable[_]] =>
          AssertAST("head")
      }

    val hasAt: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "apply", _, Some(args), _) if lhsTpe <:< weakTypeOf[Seq[_]] =>
          AssertAST("hasAt", args = args)
      }

    val hasKey: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "apply", _, Some(args), _) if lhsTpe <:< weakTypeOf[Map[_, _]] =>
          AssertAST("hasKey", args = args)
      }

    val isEmptyIterable: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "isEmpty", _, _, _) if lhsTpe <:< weakTypeOf[Iterable[_]] =>
          AssertAST("isEmptyIterable")
      }

    val isNonEmptyIterable: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "nonEmpty", _, _, _) if lhsTpe <:< weakTypeOf[Iterable[_]] =>
          AssertAST("isNonEmptyIterable")
      }

    val isEmptyOption: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "isEmpty", _, _, _) if lhsTpe <:< weakTypeOf[Option[_]] =>
          AssertAST("isEmptyOption")
      }

    val isDefinedOption: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "isDefined", _, _, _) if lhsTpe <:< weakTypeOf[Option[_]] =>
          AssertAST("isDefinedOption")
      }

    val containsSeq: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "contains", _, Some(args), _) if lhsTpe <:< weakTypeOf[Seq[_]] =>
          if (args.head.tpe.dealias <:< lhsTpe.typeArgs.head.dealias)
            AssertAST("containsSeq", args = args, tpes = List(args.head.tpe.dealias, lhsTpe.typeArgs.head.dealias))
          else
            AssertAST("containsSeq", args = args)
      }

    val containsOption: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "contains", _, Some(args), _) if lhsTpe <:< weakTypeOf[Option[_]] =>
          AssertAST("containsOption", args = args, tpes = List(lhsTpe.dealias.typeArgs.head))
      }

    val containsString: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "contains", _, Some(args), _) if lhsTpe <:< weakTypeOf[String] =>
          AssertAST("containsString", args = args)
      }

    // Option
    val asSome: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "get", _, _, _) if lhsTpe <:< weakTypeOf[Option[_]] =>
          AssertAST("isSome")
      }

    // Either
    val asRight: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "$asRight", _, _, _) if lhsTpe <:< weakTypeOf[Either[_, _]] =>
          AssertAST("asRight")
      }

    val eitherType: Type = typeOf[Either[_, _]]

    val rightGet: ASTConverter =
      new ASTConverter {
        def unapply(method: AST.Method): Option[(AST, AssertAST, (Int, Int))] =
          method match {
            case AST.Method(AST.Method(lhs, lhsTpe, _, "right", _, _, lhsSpan), _, _, "get", _, _, span)
                if lhsTpe <:< eitherType =>
              Some((lhs, AssertAST("asRight"), lhsSpan._1 -> span._2))
            case _ => None
          }
      }

    val asLeft: ASTConverter =
      ASTConverter.make {
        case AST.Method(_, lhsTpe, _, "$asLeft", _, _, _) if lhsTpe <:< eitherType =>
          AssertAST("asLeft")
      }

    val leftGet: ASTConverter =
      new ASTConverter {
        override def unapply(method: AST.Method): Option[(AST, AssertAST, (Int, Int))] =
          method match {
            case AST.Method(AST.Method(lhs, lhsTpe, _, "left", _, _, lhsSpan), _, _, "get", _, _, span)
                if lhsTpe <:< eitherType =>
              Some((lhs, AssertAST("asLeft"), lhsSpan._1 -> span._2))
            case _ => None
          }
      }

    val all: List[ASTConverter] = List(
      isEven,
      isOdd,
      equalTo,
      get,
      greaterThan,
      greaterThanOrEqualTo,
      lessThan,
      lessThanOrEqualTo,
      head,
      hasAt,
      hasKey,
      isEmptyIterable,
      isNonEmptyIterable,
      isEmptyOption,
      isDefinedOption,
      containsSeq,
      containsOption,
      containsString,
      asSome,
      asRight,
      rightGet,
      leftGet,
      asLeft,
      asInstanceOf,
      isInstanceOf
    )
  }
}
