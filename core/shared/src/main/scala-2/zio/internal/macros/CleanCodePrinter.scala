package zio.internal.macros

import scala.reflect.macros.blackbox

/**
 * removes visual clutter from scala reflect Trees.
 */

private[zio] object CleanCodePrinter {
  private val magicQuote = "-- $%^*"
  private val startQuote = s"`$magicQuote"
  private val endQuote   = s"$magicQuote`"
  private val magicArg   = "x$$$$123"
  private val tagRegex   = "\\(Tag.+?$".r.regex

  def show(c: blackbox.Context)(expr: c.Tree): String = {
    import c.universe._
    postProcess(showCode(clean(c)(cleanImplicits(c)(expr), CleanContext())))
  }

  private def postProcess(code: String): String =
    code
      .replace(startQuote, "\"")
      .replace(endQuote, "\"")
      .replace(s"($magicArg) => ", "")
      .replaceAll(tagRegex, "")

  private case class CleanContext(funcSyntheticArgs: Set[String] = Set.empty) {
    def withFuncSyntheticArgs(args: Set[String]): CleanContext = copy(funcSyntheticArgs = args)
  }

  private def clean(c: blackbox.Context)(expr: c.Tree, ctx: CleanContext): c.Tree = {
    import c.universe._
    object PackageSelects {
      def unapply(tree: c.Tree): Option[String] = packageSelects(c)(tree)
    }
    expr match {
      // remove type parameters from methods: foo[Int](args) => foo(args)
      case Apply(TypeApply(t, _), args) => Apply(clean(c)(t, ctx), cleanArgs(c)(args, ctx))
      case Apply(t, args)               => Apply(clean(c)(t, ctx), cleanArgs(c)(args, ctx))
      // foo.apply => foo
      case Select(PackageSelects(n), TermName("apply")) => Ident(TermName(cleanTupleTerm(n)))
      case Select(PackageSelects(n), TermName(name))    => Select(Ident(TermName(n)), TermName(cleanTupleTerm(name)))
      case PackageSelects(n)                            => Ident(TermName(cleanTupleTerm(n)))
      case Select(This(_), tn)                          => Ident(tn)
      case Select(left, TermName("apply"))              => clean(c)(left, ctx)
      case Select(t, n)                                 => Select(clean(c)(t, ctx), n)
      case Ident(TermName(n))                           => Ident(TermName(nameOrUnderscoreArg(n, ctx)))

      case l @ Literal(Constant(s: String)) =>
        if (s.contains("\n")) Ident(TermName(s"$magicQuote${s.replace("\n", "\\n")}$magicQuote"))
        else l
      case Typed(tree, _)      => clean(c)(tree, ctx)
      case Throw(tree)         => Throw(clean(c)(tree, ctx))
      case New(tree)           => New(clean(c)(tree, ctx))
      case CaseDef(t1, t2, t3) => CaseDef(clean(c)(t1, ctx), clean(c)(t2, ctx), clean(c)(t3, ctx))
      case Match(tree, cases) =>
        Match(
          clean(c)(tree, ctx),
          cases.map { cd =>
            (cd: @unchecked) match {
              case CaseDef(t1, t2, t3) => CaseDef(clean(c)(t1, ctx), clean(c)(t2, ctx), clean(c)(t3, ctx))
            }
          }
        )
      case Block(trees, tree) => Block(trees.map(clean(c)(_, ctx)), clean(c)(tree, ctx))
      case If(t1, t2, t3)     => If(clean(c)(t1, ctx), clean(c)(t2, ctx), clean(c)(t3, ctx))
      case Bind(n, t)         => Bind(n, clean(c)(t, ctx))
      case Function(vals, tree) =>
        val (synthetic, nonSynthetic) = vals.partition(_.mods.hasFlag(Flag.SYNTHETIC))
        val newArgs =
          if (nonSynthetic.isEmpty) List(ValDef(Modifiers(Flag.PARAM), TermName(magicArg), EmptyTree, EmptyTree))
          else cleanValDefs(c)(nonSynthetic)
        Function(
          newArgs,
          clean(c)(
            tree,
            ctx.withFuncSyntheticArgs(synthetic.collect { case ValDef(_, TermName(name), _, _) =>
              name
            }.toSet)
          )
        )
      case t => t
    }
  }

  private def cleanTupleTerm(n: String) =
    if (n.matches("Tuple\\d+")) "" else n

  private def cleanValDefs(c: blackbox.Context)(vals: List[c.universe.ValDef]) = {
    import c.universe._
    vals.map { vd =>
      (vd: @unchecked) match {
        case ValDef(mods, name, _, _) =>
          ValDef(mods, name, EmptyTree, EmptyTree)
      }
    }
  }

  private def nameOrUnderscoreArg(n: String, ctx: CleanContext) =
    if (ctx.funcSyntheticArgs(n)) "_"
    else n

  private def cleanArgs(c: blackbox.Context)(args: List[c.Tree], ctx: CleanContext): List[c.Tree] = {
    import c.universe._
    args.map(clean(c)(_, ctx)).filter {
      case Ident(TermName(name))     => !name.matches(".*\\$default\\$\\d+$")
      case Select(_, TermName(name)) => !name.matches(".*\\$default\\$\\d+$")
      case _                         => true
    }
  }

  private def packageSelects(c: blackbox.Context)(select: c.universe.Tree): Option[String] = {
    import c.universe._
    select match {
      case Select(id @ Ident(_), n: Name) if id.symbol.isPackage => Some(n.decodedName.toString)
      case Select(nested @ Select(_, _), n: Name)                => packageSelects(c)(nested).map(_ => n.decodedName.toString)
      case _                                                     => None
    }
  }

  /**
   * Remove all implicit parameter lists from the Tree
   */
  private def cleanImplicits(c: blackbox.Context)(expr: c.Tree): c.Tree = {
    import c.universe._
    val tracerType = c.weakTypeOf[zio.internal.stacktracer.Tracer.instance.Type]
    val tagType    = c.weakTypeOf[zio.Tag[_]]

    def loop(expr: c.Tree): c.Tree =
      expr match {
        case Apply(t, args) if args.exists(t => t.tpe != null && (t.tpe <:< tracerType || t.tpe <:< tagType)) =>
          loop(t)
        case Apply(t, args) =>
          Apply(loop(t), args.map(t => loop(t)))
        case Select(t, name) =>
          Select(loop(t), name)
        case TypeApply(t, args) =>
          TypeApply(loop(t), args.map(t => loop(t)))
        case Block(stats, expr) =>
          Block(stats.map(t => loop(t)), loop(expr))
        case Typed(t1, t2) =>
          Typed(loop(t1), loop(t2))
        case _ =>
          expr
      }

    loop(expr)
  }
}
