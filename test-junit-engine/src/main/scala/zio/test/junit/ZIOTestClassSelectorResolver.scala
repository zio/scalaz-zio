/*
 * Copyright 2024-2024 Vincent Raman and the ZIO Contributors
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

package zio.test.junit

import org.junit.platform.commons.support.ReflectionSupport
import org.junit.platform.commons.util.ReflectionUtils.{isAbstract, isAssignableTo, isInnerClass, isPublic}
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.discovery.{ClassSelector, ClasspathRootSelector, ModuleSelector, PackageSelector}
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.{Match, Resolution}
import zio.test._
import zio.test.junit.ReflectionUtils._

import java.util.Optional
import java.util.function.Predicate
import java.util.stream.Collectors
import scala.jdk.CollectionConverters._

/**
 * JUnit 5 platform test class resolver for ZIO test implementation
 */
class ZIOTestClassSelectorResolver extends SelectorResolver {
  private val isSuitePredicate: Predicate[Class[_]] = { (testClass: Class[_]) =>
    // valid test class are ones directly extending ZIOSpecAbstract or whose companion object
    // extends ZIOSpecAbstract
    isPublic(testClass) && !isAbstract(testClass) && !isInnerClass(testClass) &&
    (isAssignableTo(testClass, classOf[ZIOSpecAbstract]) || getCompanionObject(testClass).exists(
      _.isInstanceOf[ZIOSpecAbstract]
    ))
  }

  private val alwaysTruePredicate: Predicate[String] = _ => true

  private def classDescriptorFunction(
    testClass: Class[_]
  ): java.util.function.Function[TestDescriptor, Optional[ZIOTestClassDescriptor]] =
    (parentTestDescriptor: TestDescriptor) => {
      val suiteUniqueId =
        parentTestDescriptor.getUniqueId.append(ZIOTestClassDescriptor.segmentType, testClass.getName.stripSuffix("$"))
      val newChild = parentTestDescriptor.getChildren.asScala.find(_.getUniqueId == suiteUniqueId) match {
        case Some(_) => Optional.empty[ZIOTestClassDescriptor]()
        case None    => Optional.of(new ZIOTestClassDescriptor(parentTestDescriptor, suiteUniqueId, testClass))
      }
      newChild
    }

  private val toMatch: java.util.function.Function[TestDescriptor, java.util.stream.Stream[Match]] =
    (td: TestDescriptor) => java.util.stream.Stream.of[Match](Match.exact(td))

  private def addToParentFunction(
    context: SelectorResolver.Context
  ): java.util.function.Function[Class[_], java.util.stream.Stream[Match]] = (aClass: Class[_]) => {
    context
      .addToParent(classDescriptorFunction(aClass))
      .map[java.util.stream.Stream[Match]](toMatch)
      .orElse(java.util.stream.Stream.empty())
  }

  override def resolve(
    selector: ClasspathRootSelector,
    context: SelectorResolver.Context
  ): SelectorResolver.Resolution = {
    val matches =
      ReflectionSupport
        .findAllClassesInClasspathRoot(selector.getClasspathRoot, isSuitePredicate, alwaysTruePredicate)
        .stream()
        .flatMap(addToParentFunction(context))
        .collect(Collectors.toSet())
    Resolution.matches(matches)
  }

  override def resolve(selector: PackageSelector, context: SelectorResolver.Context): SelectorResolver.Resolution = {
    val matches =
      ReflectionSupport
        .findAllClassesInPackage(selector.getPackageName, isSuitePredicate, alwaysTruePredicate)
        .stream()
        .flatMap(addToParentFunction(context))
        .collect(Collectors.toSet())
    Resolution.matches(matches)
  }

  override def resolve(selector: ModuleSelector, context: SelectorResolver.Context): SelectorResolver.Resolution = {
    val matches =
      ReflectionSupport
        .findAllClassesInModule(selector.getModuleName, isSuitePredicate, alwaysTruePredicate)
        .stream()
        .flatMap(addToParentFunction(context))
        .collect(Collectors.toSet())
    Resolution.matches(matches)
  }

  override def resolve(selector: ClassSelector, context: SelectorResolver.Context): SelectorResolver.Resolution = {
    val testClass = selector.getJavaClass
    if (isSuitePredicate.test(testClass)) {
      context
        .addToParent(classDescriptorFunction(testClass))
        .map[Resolution]((td: TestDescriptor) => Resolution.`match`(Match.exact(td)))
        .orElse(Resolution.unresolved())
    } else {
      Resolution.unresolved()
    }
  }
}
