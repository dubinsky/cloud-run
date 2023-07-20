package org.podval.tools.cloudrun

import org.gradle.api.provider.{ListProperty, Property}
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project}
import org.gradle.process.ExecSpec
import scala.jdk.CollectionConverters.{ListHasAsScala, SeqHasAsJava}
import javax.inject.Inject

// Task and extension classes are not final so that Gradle could create decorated instances.

final class CloudRunPlugin extends Plugin[Project]:

  def apply(project: Project): Unit =
    val extension: CloudRunPlugin.Extension =
      project.getExtensions.create("cloudRun", classOf[CloudRunPlugin.Extension])

    // verify that the extension is configured properly, initialize derived values and configure JIB
    project.afterEvaluate { (project: Project) =>
      extension.getRegionValue

      Jib.configure(
        project,
        extension.service,
        extension.serviceAccountKey
      )
    }

    project.getTasks.create("cloudRunDeploy"          , classOf[CloudRunPlugin.DeployTask          ])
    project.getTasks.create("cloudRunLocal"           , classOf[CloudRunPlugin.RunLocalTask        ])
    project.getTasks.create("cloudRunDescribe"        , classOf[CloudRunPlugin.DescribeTask        ])
    project.getTasks.create("cloudRunDescribeRevision", classOf[CloudRunPlugin.DescribeRevisionTask])

object CloudRunPlugin:
  class DeployTask extends DefaultTask:
    setDescription("Deploy the service to Google Cloud Run")
    setGroup("publishing")
    Jib.dependsOn(this, Jib.remote)
    @TaskAction final def execute(): Unit = getCloudRun(this).deploy()

  class RunLocalTask extends DefaultTask:
    setDescription("Run Cloud Run service in the local Docker")
    setGroup("publishing")
    Jib.dependsOn(this, Jib.localDocker)
    private val additionalOptions: ListProperty[String] = getProject.getObjects.listProperty(classOf[String])
    @Input def getAdditionalOptions: ListProperty[String] = additionalOptions
    additionalOptions.set(Seq.empty[String].asJava)
    @TaskAction final def execute(): Unit =
      val commandLine: Seq[String] = getExtension(this).service.localDockerCommandLine(additionalOptions.get.asScala.toSeq)
      getLogger.lifecycle(s"Running: ${commandLine.mkString(" ")}")
      getProject.exec((execSpec: ExecSpec) => execSpec.setCommandLine(commandLine.asJava))

  class DescribeTask extends DefaultTask:
    setDescription("Get the Service YAML from Google Cloud Run")
    setGroup("help")
    @TaskAction final def execute(): Unit =
      getLogger.lifecycle("Latest Service YAML:\n" +
        Util.yamlObjectMapper.writeValueAsString(getCloudRun(this).get))

  class DescribeRevisionTask extends DefaultTask:
    setDescription("Get the latest Revision YAML from Google Cloud Run")
    setGroup("help")
    @TaskAction final def execute(): Unit =
      getLogger.lifecycle("Latest Revision YAML:\n" +
        Util.yamlObjectMapper.writeValueAsString(getCloudRun(this).getLatestRevision))

  private def getCloudRun(task: DefaultTask): CloudRun =
    val extension: Extension = getExtension(task)
    CloudRun(
      service = extension.service,
      serviceAccountKey = extension.serviceAccountKey,
      region = extension.getRegionValue,
      task.getLogger
    )

  private def getExtension(task: DefaultTask): Extension =
    task.getProject.getExtensions.getByType(classOf[Extension])

  abstract class Extension @Inject(project: Project):
    def getServiceAccountKeyProperty: Property[String]
    private def getServiceAccountKeyPropertyValue: String = getValue(
      property = getServiceAccountKeyProperty,
      default = Some(Key.serviceAccountKeyPropertyDefault),
      name = "serviceAccountKeyProperty"
    )
    def getServiceYamlFilePath: Property[String]
    private def getServiceYamlFilePathValue: String = getValue(
      property = getServiceYamlFilePath,
      default = Some(s"${project.getProjectDir}/service.yaml"),
      name = "serviceYamlFilePath"
    )
    def getRegion: Property[String]
    def getRegionValue: String = getValue(
      property = getRegion,
      default = None,
      name = "region"
    )
    private def getValue(property: Property[String], default: Option[String], name: String): String =
      val fromProperty: Option[String] = if !property.isPresent then None else Some(property.get)
      val result: Option[String] = fromProperty.orElse(default)
      if result.isEmpty then throw IllegalArgumentException(s"$name is not set!")
      result.get

    lazy val service: CloudRunService = CloudRunService(
      Util.readServiceYaml(getServiceYamlFilePathValue)
    )

    lazy val serviceAccountKey: Option[String] = Key.get(
      getServiceAccountKeyPropertyValue,
      project
    )
