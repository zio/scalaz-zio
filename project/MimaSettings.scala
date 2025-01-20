import com.typesafe.tools.mima.core.*
import com.typesafe.tools.mima.core.ProblemFilters.*
import com.typesafe.tools.mima.plugin.MimaKeys.*
import sbt.*
import sbt.Keys.{name, organization}
import sbtdynver.DynVerPlugin.autoImport.*

object MimaSettings {
  def mimaSettings(failOnProblem: Boolean) =
    Seq(
      mimaPreviousArtifacts ++= previousStableVersion.value.map(organization.value %% name.value % _).toSet,
      mimaBinaryIssueFilters ++= Seq(
        exclude[Problem]("zio.internal.*"),
        exclude[Problem]("zio.stm.ZSTM#internal*"),
        exclude[Problem]("zio.stm.ZSTM$internal*"),
        exclude[Problem]("zio.stream.internal*"),
        exclude[IncompatibleResultTypeProblem]("zio.stm.TRef.todo"),
        exclude[DirectMissingMethodProblem]("zio.stm.TRef.versioned_="),
        exclude[IncompatibleResultTypeProblem]("zio.stm.TRef.versioned"),
        exclude[ReversedMissingMethodProblem]("zio.Fiber#Runtime#UnsafeAPI.zio$Fiber$Runtime$UnsafeAPI$$$outer"),
        exclude[FinalClassProblem]("zio.ZPool$DefaultPool"),
        exclude[DirectMissingMethodProblem]("zio.ZPool#DefaultPool.invalidated"),
        exclude[ReversedMissingMethodProblem]("zio.Scope#Closeable.size"),
        exclude[Problem]("zio.Scope#ReleaseMap*"),
        exclude[Problem]("zio.Scope$ReleaseMap*"),
        exclude[MissingClassProblem]("zio.Scope$Running*"),
        exclude[MissingClassProblem]("zio.Scope$Exited*"),
        exclude[Problem]("zio.Scope$State$Exited*"),
        exclude[Problem]("zio.Scope#State#Exited*"),
        exclude[NewMixinForwarderProblem]("zio.Exit.as"),
        exclude[NewMixinForwarderProblem]("zio.Exit.fold"),
        exclude[NewMixinForwarderProblem]("zio.Exit.foldCause"),
        exclude[NewMixinForwarderProblem]("zio.Exit.map"),
        exclude[NewMixinForwarderProblem]("zio.Exit.mapBoth"),
        exclude[NewMixinForwarderProblem]("zio.Exit.mapError"),
        exclude[NewMixinForwarderProblem]("zio.Exit.mapErrorCause"),
        exclude[NewMixinForwarderProblem]("zio.Exit.unit")
      ),
      mimaFailOnProblem := failOnProblem
    )
}
