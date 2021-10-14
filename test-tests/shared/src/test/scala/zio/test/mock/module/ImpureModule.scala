package zio.test.mock.module

import com.github.ghik.silencer.silent
import zio.{Tag, URIO, ZIO}

/**
 * Example of impure module used for testing ZIO Mock framework.
 */
object ImpureModule {

  trait Service {

    def zeroParams: String
    def zeroParamsWithParens(): String
    def singleParam(a: Int): String
    def manyParams(a: Int, b: String, c: Long): String
    def manyParamLists(a: Int)(b: String)(c: Long): String
    @silent("side-effecting nullary methods")
    def command: Unit
    def parameterizedCommand(a: Int): Unit
    def overloaded(n: Int): String
    def overloaded(n: Long): String
    def polyInput[I: Tag](v: I): String
    def polyError[E <: Throwable: Tag](v: String): String
    def polyOutput[A: Tag](v: String): A
    def polyInputError[I: Tag, E <: Throwable: Tag](v: I): String
    def polyInputOutput[I: Tag, A: Tag](v: I): A
    def polyErrorOutput[E <: Throwable: Tag, A: Tag](v: String): A
    def polyInputErrorOutput[I: Tag, E <: Throwable: Tag, A: Tag](v: I): A
    def polyMixed[A: Tag]: (A, String)
    def polyBounded[A <: AnyVal: Tag]: A
    def varargs(a: Int, b: String*): String
    def curriedVarargs(a: Int, b: String*)(c: Long, d: Char*): String
    def byName(a: => Int): String
    def maxParams(
      a: Int,
      b: Int,
      c: Int,
      d: Int,
      e: Int,
      f: Int,
      g: Int,
      h: Int,
      i: Int,
      j: Int,
      k: Int,
      l: Int,
      m: Int,
      n: Int,
      o: Int,
      p: Int,
      q: Int,
      r: Int,
      s: Int,
      t: Int,
      u: Int,
      v: Int
    ): String
  }

  def zeroParams: URIO[ImpureModule, String]             = ZIO.access[ImpureModule](_.get.zeroParams)
  def zeroParamsWithParens(): URIO[ImpureModule, String] = ZIO.access[ImpureModule](_.get.zeroParamsWithParens())
  def singleParam(a: Int): URIO[ImpureModule, String]    = ZIO.access[ImpureModule](_.get.singleParam(a))
  def manyParams(a: Int, b: String, c: Long): URIO[ImpureModule, String] =
    ZIO.access[ImpureModule](_.get.manyParams(a, b, c))
  def manyParamLists(a: Int)(b: String)(c: Long): URIO[ImpureModule, String] =
    ZIO.access[ImpureModule](_.get.manyParamLists(a)(b)(c))
  def command: URIO[ImpureModule, Unit]                      = ZIO.access[ImpureModule](_.get.command)
  def parameterizedCommand(a: Int): URIO[ImpureModule, Unit] = ZIO.access[ImpureModule](_.get.parameterizedCommand(a))
  def overloaded(n: Int): URIO[ImpureModule, String]         = ZIO.access[ImpureModule](_.get.overloaded(n))
  def overloaded(n: Long): URIO[ImpureModule, String]        = ZIO.access[ImpureModule](_.get.overloaded(n))
  def polyInput[I: Tag](v: I): URIO[ImpureModule, String]    = ZIO.access[ImpureModule](_.get.polyInput(v))
  def polyError[E <: Throwable: Tag](v: String): URIO[ImpureModule, String] =
    ZIO.access[ImpureModule](_.get.polyError(v))
  def polyOutput[A: Tag](v: String): URIO[ImpureModule, A] = ZIO.access[ImpureModule](_.get.polyOutput(v))
  def polyInputError[I: Tag, E <: Throwable: Tag](v: I): URIO[ImpureModule, String] =
    ZIO.access[ImpureModule](_.get.polyInputError[I, E](v))
  def polyInputOutput[I: Tag, A: Tag](v: I): URIO[ImpureModule, A] =
    ZIO.access[ImpureModule](_.get.polyInputOutput[I, A](v))
  def polyErrorOutput[E <: Throwable: Tag, A: Tag](v: String): URIO[ImpureModule, A] =
    ZIO.access[ImpureModule](_.get.polyErrorOutput[E, A](v))
  def polyInputErrorOutput[I: Tag, E <: Throwable: Tag, A: Tag](v: I): URIO[ImpureModule, A] =
    ZIO.access[ImpureModule](_.get.polyInputErrorOutput[I, E, A](v))
  def polyMixed[A: Tag]: URIO[ImpureModule, (A, String)]      = ZIO.access[ImpureModule](_.get.polyMixed[A])
  def polyBounded[A <: AnyVal: Tag]: URIO[ImpureModule, A]    = ZIO.access[ImpureModule](_.get.polyBounded[A])
  def varargs(a: Int, b: String*): URIO[ImpureModule, String] = ZIO.access[ImpureModule](_.get.varargs(a, b: _*))
  def curriedVarargs(a: Int, b: String*)(c: Long, d: Char*): URIO[ImpureModule, String] =
    ZIO.access[ImpureModule](_.get.curriedVarargs(a, b: _*)(c, d: _*))
  def byName(a: => Int): URIO[ImpureModule, String] = ZIO.access[ImpureModule](_.get.byName(a))
  def maxParams(
    a: Int,
    b: Int,
    c: Int,
    d: Int,
    e: Int,
    f: Int,
    g: Int,
    h: Int,
    i: Int,
    j: Int,
    k: Int,
    l: Int,
    m: Int,
    n: Int,
    o: Int,
    p: Int,
    q: Int,
    r: Int,
    s: Int,
    t: Int,
    u: Int,
    v: Int
  ): URIO[ImpureModule, String] =
    ZIO.access[ImpureModule](_.get.maxParams(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v))
}
