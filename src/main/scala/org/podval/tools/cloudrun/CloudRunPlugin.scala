package org.podval.tools.cloudrun

import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project}
import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.process.ExecSpec
import scala.jdk.CollectionConverters.{ListHasAsScala, SeqHasAsJava}

final class CloudRunPlugin extends Plugin[Project] {

  def apply(project: Project): Unit = {
    project.getExtensions.create(
      CloudRunExtension.extensionName,
      classOf[CloudRunExtension],
      project
    )

    project.getTasks.create("cloudRunDeploy", classOf[CloudRunPlugin.DeployTask])
    project.getTasks.create("cloudRunLocal", classOf[CloudRunPlugin.RunLocalTask])
    project.getTasks.create("cloudRunDescribe", classOf[CloudRunPlugin.DescribeTask])
    project.getTasks.create("cloudRunDescribeLatestRevision", classOf[CloudRunPlugin.DescribeLatestRevisionTask])
  }
}

object CloudRunPlugin {

  // Extension with the name 'jib' is assumed to be created by the
  // [JIB plugin](https://github.com/GoogleContainerTools/jib);
  // it is then of the type com.google.cloud.tools.jib.gradle.JibExtension,
  // and tasks 'jib' and 'jibDockerBuild' exist.
  def getJib(project: Project): Option[JibExtension] =
    Option(project.getExtensions.findByName("jib")).map(_.asInstanceOf[JibExtension])

  // Task classes are not final so that Gradle could create decorated instances.

  class DeployTask extends DefaultTask {
    setDescription("Deploy the service to Google Cloud Run")
    setGroup("publishing")

    getProject.afterEvaluate((project: Project) =>
      if (getJib(project).isDefined) dependsOn(project.getTasks.getByPath("jib")))

    @TaskAction final def execute(): Unit = CloudRunExtension.getService(getProject).deploy()
  }

  class RunLocalTask extends DefaultTask {
    setDescription("Run Cloud Run service in the local Docker")
    setGroup("publishing")

    private val additionalOptions: ListProperty[String] = getProject.getObjects.listProperty(classOf[String])
    @Input def getAdditionalOptions: ListProperty[String] = additionalOptions
    additionalOptions.set(Seq.empty[String].asJava)

    getProject.afterEvaluate((project: Project) =>
      if (getJib(project).isDefined) dependsOn(project.getTasks.getByPath("jibDockerBuild")))

    @TaskAction final def execute(): Unit = {
      val commandLine: Seq[String] = ServiceExtender.dockerCommandLine(
        CloudRunExtension.get(getProject).service,
        additionalOptions.get.asScala.toSeq
      )
      getLogger.lifecycle(s"Running: ${commandLine.mkString(" ")}")
      getProject.exec((execSpec: ExecSpec) => execSpec.setCommandLine(commandLine.asJava))
    }
  }

  class DescribeTask extends DefaultTask {
    setDescription("Get the Service YAML from Google Cloud Run")
    setGroup("help")

    @TaskAction final def execute(): Unit = getProject.getLogger.lifecycle(
      "Latest Service YAML:\n" + CloudRun.json2yaml(CloudRunExtension.getService(getProject).get)
    )
  }

  class DescribeLatestRevisionTask extends DefaultTask {
    setDescription("Get the latest Revision YAML from Google Cloud Run")
    setGroup("help")

    @TaskAction final def execute(): Unit = getProject.getLogger.lifecycle(
      "Latest Revision YAML:\n" + CloudRun.json2yaml(CloudRunExtension.getService(getProject).getLatestRevision)
    )
  }
}
