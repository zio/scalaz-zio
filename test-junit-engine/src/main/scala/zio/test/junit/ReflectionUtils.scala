package zio.test.junit

import scala.util.Try

private[zio] object ReflectionUtils {

  /**
   * Retrieves the companion object of the specified class, if it exists. Here
   * we use plain java reflection as runtime reflection is not available for
   * scala 3
   *
   * @param klass
   *   The class for which to retrieve the companion object.
   * @return
   *   the optional companion object.
   */
  def getCompanionObject(klass: Class[_]): Option[Any] =
    Try {
      (if (klass.getName.endsWith("$")) klass else getClass.getClassLoader.loadClass(klass.getName + "$"))
        .getDeclaredField("MODULE$")
        .get(null)
    }.toOption
}
