import sbt._
import sbt.Keys.{name, organization}

import com.typesafe.tools.mima.plugin.MimaKeys._
import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.core.ProblemFilters._

object MimaSettings {
  lazy val bincompatVersionToCompare = "2.0.3"

  def mimaSettings(failOnProblem: Boolean) =
    Seq(
      mimaPreviousArtifacts := Set(organization.value %% name.value % bincompatVersionToCompare),
      mimaBinaryIssueFilters ++= Seq(
        exclude[Problem]("zio.internal.*"),
        exclude[FinalMethodProblem]("zio.ZIO#EvaluationStep#*"),
        exclude[MissingClassProblem]("zio.Secret$"),
        exclude[MissingClassProblem]("zio.Secret"),
        exclude[IncompatibleMethTypeProblem]("zio.Config#*"),
        exclude[DirectMissingMethodProblem]("zio.Config#*"),
        exclude[DirectMissingMethodProblem]("zio.Config.*"),
        exclude[MissingTypesProblem]("zio.Config$Secret$")
      ),
      mimaFailOnProblem := failOnProblem
    )
}
