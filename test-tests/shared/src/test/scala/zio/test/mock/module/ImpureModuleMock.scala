package zio.test.mock.module

import com.github.ghik.silencer.silent
import zio.test.mock.{Mock, Proxy}
import zio.{Has, Tag, URLayer, ZLayer}

/**
 * Example module used for testing ZIO Mock framework.
 */
object ImpureModuleMock extends Mock[ImpureModule] {

  object ZeroParams           extends Method[Unit, Throwable, String]
  object ZeroParamsWithParens extends Method[Unit, Throwable, String]
  object SingleParam          extends Method[Int, Throwable, String]
  object ManyParams           extends Method[(Int, String, Long), Throwable, String]
  object ManyParamLists       extends Method[(Int, String, Long), Throwable, String]
  object Command              extends Method[Unit, Throwable, Unit]
  object ParameterizedCommand extends Method[Int, Throwable, Unit]
  object PolyInput            extends Poly.Method.Input[Throwable, String]
  object PolyError            extends Poly.Method.Error[String, String]
  object PolyOutput           extends Poly.Method.Output[String, Throwable]
  object PolyInputError       extends Poly.Method.InputError[String]
  object PolyInputOutput      extends Poly.Method.InputOutput[Throwable]
  object PolyErrorOutput      extends Poly.Method.ErrorOutput[String]
  object PolyInputErrorOutput extends Poly.Method.InputErrorOutput
  object PolyMixed            extends Poly.Method.Output[Unit, Throwable]
  object PolyBounded          extends Poly.Method.Output[Unit, Throwable]
  object Varargs              extends Method[(Int, Seq[String]), Throwable, String]
  object CurriedVarargs       extends Method[(Int, Seq[String], Long, Seq[Char]), Throwable, String]
  object ByName               extends Effect[Int, Throwable, String]

  object Overloaded {
    object _0 extends Method[Int, Throwable, String]
    object _1 extends Method[Long, Throwable, String]
  }

  object MaxParams extends Method[T22[Int], Throwable, String]

  val compose: URLayer[Has[Proxy], ImpureModule] =
    ZLayer.fromServiceM { proxy =>
      withRuntime.map { rts =>
        new ImpureModule.Service {
          def zeroParams: String                                 = rts.unsafeRunTask(proxy(ZeroParams))
          def zeroParamsWithParens(): String                     = rts.unsafeRunTask(proxy(ZeroParamsWithParens))
          def singleParam(a: Int): String                        = rts.unsafeRunTask(proxy(SingleParam, a))
          def manyParams(a: Int, b: String, c: Long): String     = rts.unsafeRunTask(proxy(ManyParams, (a, b, c)))
          def manyParamLists(a: Int)(b: String)(c: Long): String = rts.unsafeRunTask(proxy(ManyParamLists, a, b, c))
          @silent("side-effecting nullary methods")
          def command: Unit                                     = rts.unsafeRunTask(proxy(Command))
          def parameterizedCommand(a: Int): Unit                = rts.unsafeRunTask(proxy(ParameterizedCommand, a))
          def overloaded(n: Int): String                        = rts.unsafeRunTask(proxy(Overloaded._0, n))
          def overloaded(n: Long): String                       = rts.unsafeRunTask(proxy(Overloaded._1, n))
          def polyInput[I: Tag](v: I): String                   = rts.unsafeRunTask(proxy(PolyInput.of[I], v))
          def polyError[E <: Throwable: Tag](v: String): String = rts.unsafeRunTask(proxy(PolyError.of[E], v))
          def polyOutput[A: Tag](v: String): A                  = rts.unsafeRunTask(proxy(PolyOutput.of[A], v))
          def polyInputError[I: Tag, E <: Throwable: Tag](v: I): String =
            rts.unsafeRunTask(proxy(PolyInputError.of[I, E], v))
          def polyInputOutput[I: Tag, A: Tag](v: I): A = rts.unsafeRunTask(proxy(PolyInputOutput.of[I, A], v))
          def polyErrorOutput[E <: Throwable: Tag, A: Tag](v: String): A =
            rts.unsafeRunTask(proxy(PolyErrorOutput.of[E, A], v))
          def polyInputErrorOutput[I: Tag, E <: Throwable: Tag, A: Tag](v: I): A =
            rts.unsafeRunTask(proxy(PolyInputErrorOutput.of[I, E, A], v))
          def polyMixed[A: Tag]: (A, String)      = rts.unsafeRunTask(proxy(PolyMixed.of[(A, String)]))
          def polyBounded[A <: AnyVal: Tag]: A    = rts.unsafeRunTask(proxy(PolyBounded.of[A]))
          def varargs(a: Int, b: String*): String = rts.unsafeRunTask(proxy(Varargs, (a, b)))
          def curriedVarargs(a: Int, b: String*)(c: Long, d: Char*): String =
            rts.unsafeRunTask(proxy(CurriedVarargs, (a, b, c, d)))
          def byName(a: => Int): String = rts.unsafeRunTask(proxy(ByName, a))
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
          ): String =
            rts.unsafeRunTask(proxy(MaxParams, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v)))
        }
      }
    }
}
