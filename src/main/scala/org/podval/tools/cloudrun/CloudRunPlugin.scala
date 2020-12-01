package org.podval.tools.cloudrun

import org.gradle.api.provider.Property
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project, Task}
import scala.beans.BeanProperty
import com.google.cloud.tools.jib.gradle.{AuthParameters, JibExtension, TargetImageParameters}

final class CloudRunPlugin extends Plugin[Project] {

  def apply(project: Project): Unit = {
    val extension: CloudRunPlugin.Extension =
      project.getExtensions.create("cloudRun", classOf[CloudRunPlugin.Extension], project)

    def wireTask[T <: CloudRunPlugin.ServiceTask](name: String, clazz: Class[T]): T = {
      val result: T = project.getTasks.create[T](name, clazz)
      result.cloudRunService.set(project.provider(() => extension.service))
      result
    }

    val deployTask: CloudRunPlugin.DeployTask =
      wireTask("cloudRunDeploy", classOf[CloudRunPlugin.DeployTask])

    wireTask("cloudRunGetServiceYaml", classOf[CloudRunPlugin.GetServiceYamlTask])
    wireTask("cloudRunGetLatestRevisionYaml", classOf[CloudRunPlugin.GetLatestRevisionYamlTask])

    // Extension with the name 'jib' is assumed to be created by the
    // [JIB plugin](https://github.com/GoogleContainerTools/jib);
    // it is then of the type com.google.cloud.tools.jib.gradle.JibExtension, and task 'jib' exists.
    project.afterEvaluate((project: Project) =>
      Option(project.getExtensions.findByName("jib"))
        .map(_.asInstanceOf[JibExtension])
        .foreach { jibExtension => configureJib(
          project,
          extension,
          deployTask,
          jibExtension,
          project.getTasks.findByPath("jib")
        )}
    )
  }

  private def configureJib(
    project: Project,
    extension: CloudRunPlugin.Extension,
    deployTask: CloudRunPlugin.DeployTask,
    jibExtension: JibExtension,
    jibTask: Task
  ): Unit = {
    def log(message: String): Unit = project.getLogger.info(message, null, null, null)
      deployTask.dependsOn(jibTask)

      val to: TargetImageParameters = jibExtension.getTo
      if (to.getImage == null) {
        to.setImage(project.provider(() => extension.service.containerImage))
        log("CloudRun: configured 'jib.to.image'.")
      }

      val auth: AuthParameters = to.getAuth

      if (auth.getUsername == null) {
        auth.setUsername("_json_key")
        log("CloudRun: configured 'jib.to.auth.username'.")
      }

      // see https://github.com/dubinsky/cloud-run/issues/6
      jibTask.doFirst((_: Task) => if (auth.getPassword == null) {
        auth.setPassword(extension.serviceAccountKey)
        log("CloudRun: configured 'jib.to.auth.password'.")
      })
    }
}

object CloudRunPlugin {

  // Properties are annotated with @BeanProperty to make them visible to Gradle.
  // Classes are not final so that Gradle could create decorated instances.

  class Extension(project: Project) {
    @BeanProperty val region: Property[String] = project.getObjects.property(classOf[String])

    @BeanProperty val serviceAccountKeyProperty: Property[String] = project.getObjects.property(classOf[String])
    serviceAccountKeyProperty.set(CloudRun.cloudServiceAccountKeyPropertyDefault)

    @BeanProperty val serviceYamlFilePath: Property[String] = project.getObjects.property(classOf[String])
    serviceYamlFilePath.set(s"${project.getProjectDir}/service.yaml")

    lazy val service: CloudRun.ForService = new CloudRun.ForService(
      run = new CloudRun(
        serviceAccountKey,
        region = getValue(region, "region")
      ),
      serviceYamlFilePath = getValue(serviceYamlFilePath, "serviceYamlFilePath")
    )

    lazy val serviceAccountKey: String = {
      val keyProperty: String = getValue(serviceAccountKeyProperty, "serviceAccountKeyProperty")
      if (keyProperty.isEmpty) throw new IllegalArgumentException()
      if (keyProperty.startsWith("/")) {
        val key: String = CloudRun.file2string(keyProperty)
        project.getLogger.lifecycle(
          "Add the following property to your .gradle/gradle.properties file:\n" +
           CloudRun.key2property(key)
        )
        key
      } else Option(System.getenv(keyProperty))
        .orElse(Option(project.findProperty(keyProperty).asInstanceOf[String]))
        .getOrElse(throw new IllegalArgumentException(
          "Service account key not defined" +
          s" (looked at environment variable and property $keyProperty)"
        ))
    }

    private def getValue(property: Property[String], name: String): String = {
      val result: String = property.get()
      if (result.isEmpty) throw new IllegalArgumentException(s"$name is not set!")
      result
    }
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
