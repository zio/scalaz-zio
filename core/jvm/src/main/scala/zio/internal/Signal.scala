package zio.internal

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.function.Consumer
import java.util.logging.Logger

/**
 * * This class is used to handle system signals in a platform-specific way. It
 * attemps to use the sun.misc.Signal and sun.misc.SignalHandler classes if they
 * are available. If they are not available, it will gracefully degrade to a
 * no-op.
 *
 * '''IMPLEMENTATION NOTE''': This class uses reflection to access the
 * sun.misc.Signal and sun.misc.SignalHandler. This is necessary because the
 * sun.misc.Signal and sun.misc.SignalHandler classes might not be available in
 * some JVMs.
 */
private object Signal {

  private val signalHandler: Signal.SignalHandler = {
    val logger             = Logger.getLogger("zio.internal.Signal")
    val signalClass        = findClass("sun.misc.Signal")
    val signalHandlerClass = findClass("sun.misc.SignalHandler")

    var constructorHandle: MethodHandle  = null
    var staticMethodHandle: MethodHandle = null

    if ((signalClass ne null) && (signalHandlerClass ne null)) {
      val lookup = MethodHandles.lookup()
      constructorHandle = initSignalConstructorMethodHandle(lookup, signalClass)
      staticMethodHandle = initHandleStaticMethodHandle(lookup, signalClass, signalHandlerClass)
    } else {
      constructorHandle = null
      staticMethodHandle = null
    }

    if ((signalHandlerClass ne null) && (constructorHandle ne null) && (staticMethodHandle ne null))
      new SignalHandler.SunMiscSignalHandler(signalHandlerClass, constructorHandle, staticMethodHandle)
    else {
      logger.warning(
        "sun.misc.Signal and sun.misc.SignalHandler are not available on this platform. Defaulting to no-op signal handling implementation; ZIO fiber dump functionality might not work as expected."
      )
      new SignalHandler.NoOpSignalHandler
    }
  }

  private[internal] def handle(signal: String, handler: Consumer[AnyRef]): Unit =
    signalHandler.handle(signal, handler)

  private def findClass(name: String): Class[?] =
    try {
      Class.forName(name)
    } catch {
      case _: ClassNotFoundException => null
    }

  private def initSignalConstructorMethodHandle(
    lookup: MethodHandles.Lookup,
    signalClass: Class[?]
  ): MethodHandle = {
    val signalConstructorMethodType = MethodType.methodType(classOf[Unit], classOf[String])
    try {
      lookup.findConstructor(signalClass, signalConstructorMethodType)
    } catch {
      case _: Exception => null
    }
  }

  private def initHandleStaticMethodHandle(
    lookup: MethodHandles.Lookup,
    signalClass: Class[?],
    signalHandlerClass: Class[?]
  ) =
    try {
      val handleStaticMethodType = MethodType.methodType(signalHandlerClass, signalClass, signalHandlerClass)
      lookup.findStatic(signalClass, "handle", handleStaticMethodType)
    } catch {
      case _: Exception => null
    }

  private abstract sealed class SignalHandler {
    def handle(signal: String, handler: Consumer[AnyRef]): Unit
  }

  private object SignalHandler {

    final class SunMiscSignalHandler(
      signalHandlerClass: Class[?],
      constuctorMethodHandle: MethodHandle,
      staticMethodHandle: MethodHandle
    ) extends Signal.SignalHandler {

      override def handle(signal: String, handler: Consumer[AnyRef]): Unit = {
        val invocationHandler = initInvocationHandler(handler)
        val proxy = Proxy.newProxyInstance(
          Signal.getClass.getClassLoader,
          Array(signalHandlerClass),
          invocationHandler
        )
        try {
          val s = constuctorMethodHandle.invoke(signal)
          staticMethodHandle.invoke(s, proxy)
        } catch {
          // Gracefully degrade to a no-op
          case _: Throwable => ()
        }
      }

      private def initInvocationHandler(handler: Consumer[AnyRef]): InvocationHandler =
        (proxy: Any, method: Method, args: Array[AnyRef]) => {
          if (args.nonEmpty) handler.accept(args(0))
          null
        }

    }

    final class NoOpSignalHandler extends SignalHandler {
      override def handle(signal: String, handler: Consumer[AnyRef]): Unit = ()
    }
  }
}
