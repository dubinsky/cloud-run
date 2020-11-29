package org.podval.tools.cloudrun

import org.gradle.api.provider.Property
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project}
import scala.beans.BeanProperty

final class CloudRunPlugin extends Plugin[Project] {

  def apply(project: Project): Unit = {
    val extension: CloudRunPlugin.Extension =
      project.getExtensions.create("cloudRun", classOf[CloudRunPlugin.Extension], project)

    def wireTask[T <:CloudRunPlugin.CloudRunServiceTask](name: String, clazz: Class[T]): Unit =
      project.getTasks.create[T](name, clazz).cloudRunService.set(extension.getCloudRunService)

    wireTask("cloudRunDeploy", classOf[CloudRunPlugin.DeployTask])
    wireTask("cloudRunGetServiceYaml", classOf[CloudRunPlugin.GetServiceYamlTask])
    wireTask("cloudRunGetLatestRevisionYaml", classOf[CloudRunPlugin.GetLatestRevisionYamlTask])
  }
}

object CloudRunPlugin {

  // Properties are annotated with @BeanProperty to make them visible to Gradle.
  // Classes are not final so that Gradle could create instances of them.

  class Extension(project: Project) {

    @BeanProperty
    val serviceAccountKey: Property[String] = newStringProperty

    @BeanProperty
    val region: Property[String] = newStringProperty

    @BeanProperty
    val serviceYamlFilePath: Property[String] = newStringProperty

    // Defaults
    serviceYamlFilePath.set(s"${project.getProjectDir}/service.yaml")

    // read-only properties exposed by the extension after project evaluation

    private val cloudRunService: Property[CloudRun.ForService] = project.getObjects.property(classOf[CloudRun.ForService])

    def getCloudRunService: Property[CloudRun.ForService] = cloudRunService

    private val containerImage: Property[String] = newStringProperty

    // jib.to.image is not a property, so I can't reuse the one exposed on the extension to set it...
    def getContainerImage: Property[String] = containerImage

    private def newStringProperty: Property[String] = project.getObjects.property(classOf[String])

    project.afterEvaluate((_: Project) => {
      val service = new CloudRun(
        CloudRun.keyOrFile2credentials(serviceAccountKey.get),
        region = region.get
      ).forServiceYaml(serviceYamlFilePath.get)

      cloudRunService.set(service)
      containerImage.set(service.containerImage)
    })
  }

  abstract class CloudRunServiceTask extends DefaultTask {

    @Input @BeanProperty
    final val cloudRunService: Property[CloudRun.ForService] = getProject.getObjects.property(classOf[CloudRun.ForService])

    @TaskAction
    final def execute(): Unit = execute(cloudRunService.get)

    protected def execute(cloudRunService: CloudRun.ForService): Unit
  }

  class DeployTask extends CloudRunServiceTask {
    setDescription(s"Deploy the service to Google Cloud Run")
    setGroup("publishing")

    override protected def execute(cloudRunService: CloudRun.ForService): Unit =
      cloudRunService.deploy
  }

  class GetServiceYamlTask extends CloudRunServiceTask {
    setDescription(s"Get the service YAML from Google Cloud Run")
    setGroup("help")

    override protected def execute(cloudRunService: CloudRun.ForService): Unit =
      getProject.getLogger.lifecycle(cloudRunService.getServiceYaml)
  }

  class GetLatestRevisionYamlTask extends CloudRunServiceTask {
    setDescription(s"Get the latest revision YAML from Google Cloud Run")
    setGroup("help")

    override protected def execute(cloudRunService: CloudRun.ForService): Unit =
      getProject.getLogger.lifecycle(cloudRunService.getLatestRevisionYaml)
  }
}
