package org.podval.tools.cloudrun

import com.google.auth.oauth2.ServiceAccountCredentials
import org.gradle.api.provider.Property
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project}
import scala.beans.BeanProperty

final class CloudRunPlugin extends Plugin[Project] {

  def apply(project: Project): Unit = {
    val extension: CloudRunPlugin.Extension =
      project.getExtensions.create("cloudRun", classOf[CloudRunPlugin.Extension], project)

    def wireTask[T <: CloudRunPlugin.ServiceTask](name: String, clazz: Class[T]): Unit =
      project.getTasks.create[T](name, clazz).cloudRunService.set(extension.getCloudRunService)

    wireTask("cloudRunDeploy", classOf[CloudRunPlugin.DeployTask])
    wireTask("cloudRunGetServiceYaml", classOf[CloudRunPlugin.GetServiceYamlTask])
    wireTask("cloudRunGetLatestRevisionYaml", classOf[CloudRunPlugin.GetLatestRevisionYamlTask])
  }
}

object CloudRunPlugin {

  // Properties are annotated with @BeanProperty to make them visible to Gradle.
  // Classes are not final so that Gradle could create decorated instances.

  class Extension(project: Project) {

    @BeanProperty val serviceAccountKeyProperty: Property[String] = newStringProperty

    @BeanProperty val region: Property[String] = newStringProperty

    @BeanProperty val serviceYamlFilePath: Property[String] = newStringProperty

    // Defaults
    serviceAccountKeyProperty.set("gcloudServiceAccountKey")
    serviceYamlFilePath.set(s"${project.getProjectDir}/service.yaml")

    // read-only properties exposed by the extension after project evaluation

    private val cloudRunService: Property[CloudRun.ForService] =
      project.getObjects.property(classOf[CloudRun.ForService])

    def getCloudRunService: Property[CloudRun.ForService] = cloudRunService

    private val containerImage: Property[String] = newStringProperty

    // jib.to.image property is used at evaluation time,
    // so I can't reuse the one exposed on the extension to set it...
    def getContainerImage: Property[String] = containerImage

    private def newStringProperty: Property[String] = project.getObjects.property(classOf[String])

    project.afterEvaluate((project: Project) => {
      val keyProperty: String = serviceAccountKeyProperty.get
      val key: String =
        if (keyProperty.startsWith("/")) {
          val key: String = CloudRun.file2string(keyProperty)
          project.getLogger.lifecycle(
            "Add the following property to your .gradle/gradle.properties file:\n" +
            CloudRun.key2property(key)
          )
          key
        } else
          Option(System.getenv(keyProperty))
          .orElse(Option(project.findProperty(keyProperty).asInstanceOf[String]))
          .getOrElse(throw new IllegalArgumentException(
            "Service account key not defined" +
            s"(looked at environment variable and property $keyProperty)"
          ))

      // TODO throw exception if any of the config parameters are empty (or empty strings).

      val service = new CloudRun(key, region.get).forServiceYaml(serviceYamlFilePath.get)

      cloudRunService.set(service)
      containerImage.set(service.containerImage)
    })
  }

  abstract class ServiceTask(
    description: String,
    group: String
  ) extends DefaultTask {
    setDescription(description)
    setGroup(group)

    @Input @BeanProperty final val cloudRunService: Property[CloudRun.ForService] =
      getProject.getObjects.property(classOf[CloudRun.ForService])

    @TaskAction final def execute(): Unit = execute(cloudRunService.get)

    protected def execute(cloudRunService: CloudRun.ForService): Unit
  }

  class DeployTask extends ServiceTask(
    description = "Deploy the service to Google Cloud Run",
    group = "publishing"
  ) {
    override protected def execute(cloudRunService: CloudRun.ForService): Unit =
      cloudRunService.deploy
  }

  class GetServiceYamlTask extends ServiceTask(
    description = "Get the service YAML from Google Cloud Run",
    group = "help"
  ) {
    override protected def execute(cloudRunService: CloudRun.ForService): Unit =
      getProject.getLogger.lifecycle(cloudRunService.getServiceYaml)
  }

  class GetLatestRevisionYamlTask extends ServiceTask(
    description = "Get the latest revision YAML from Google Cloud Run",
    group = "help"
  ) {
    override protected def execute(cloudRunService: CloudRun.ForService): Unit =
      getProject.getLogger.lifecycle(cloudRunService.getLatestRevisionYaml)
  }
}
