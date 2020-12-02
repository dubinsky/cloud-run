package org.podval.tools.cloudrun

import org.gradle.api.provider.Property
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project, Task}
import com.google.cloud.tools.jib.gradle.{AuthParameters, JibExtension, TargetImageParameters}
import scala.util.Try

final class CloudRunPlugin extends Plugin[Project] {

  def apply(project: Project): Unit = {
    val extension: CloudRunPlugin.Extension =
      project.getExtensions.create("cloudRun", classOf[CloudRunPlugin.Extension], project)

    def wireTask[T <: CloudRunPlugin.ServiceTask](name: String, clazz: Class[T]): T = {
      val result: T = project.getTasks.create[T](name, clazz)
      result.getCloudRunService.set(project.provider(() => extension.service))
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
          jibTask = project.getTasks.findByPath("jib")
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

    // see https://github.com/dubinsky/cloud-run/issues/6;
    if (auth.getPassword == null) {
//      auth.setPassword(project.provider(() => extension.serviceAccountKey))
      jibTask.doFirst((_: Task) => {
        auth.setPassword(extension.serviceAccountKey)
        log("CloudRun: configured 'jib.to.auth.password'.")
      })
    }
  }
}

object CloudRunPlugin {

  private val serviceAccountKeyPropertyDefault: String = "gcloudServiceAccountKey"

  // Extension and Task classes are not final so that Gradle could create decorated instances.

  class Extension(project: Project) {
    private val region: Property[String] = project.getObjects.property(classOf[String])
    def getRegion: Property[String] = region

    private val serviceAccountKeyProperty: Property[String] = project.getObjects.property(classOf[String])
    def getServiceAccountKeyProperty: Property[String] = serviceAccountKeyProperty
    serviceAccountKeyProperty.set(serviceAccountKeyPropertyDefault)

    private val serviceYamlFilePath: Property[String] = project.getObjects.property(classOf[String])
    def getServiceYamlFilePath: Property[String] = serviceYamlFilePath
    serviceYamlFilePath.set(s"${project.getProjectDir}/service.yaml")

    lazy val service: CloudRunService = CloudRunService(
      serviceAccountKey,
      region = getValue(region, "region"),
      serviceYamlFilePath = getValue(serviceYamlFilePath, "serviceYamlFilePath")
    )

    lazy val serviceAccountKey: String = {
      val keyProperty: String = getValue(serviceAccountKeyProperty, "serviceAccountKeyProperty")
      if (keyProperty.isEmpty) throw new IllegalArgumentException()
      if (keyProperty.startsWith("/")) {
        val key: String = CloudRun.file2string(keyProperty)
        project.getLogger.lifecycle(
          "Add the following property to your .gradle/gradle.properties file:\n" +
          serviceAccountKeyPropertyDefault  + "= \\\n" +
          key
            .replace("\n", " \\\n")
            .replace("\\n", "\\\\n")
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

    private val cloudRunService: Property[CloudRunService] =
      getProject.getObjects.property(classOf[CloudRunService])

    @Input def getCloudRunService: Property[CloudRunService] = cloudRunService

    @TaskAction final def execute(): Unit = execute(cloudRunService.get)

    protected def execute(cloudRunService: CloudRunService): Unit
  }

  class DeployTask extends ServiceTask(
    description = "Deploy the service to Google Cloud Run",
    group = "publishing"
  ) {
    override protected def execute(cloudRunService: CloudRunService): Unit = {
      def log(message: String): Unit = getProject.getLogger.lifecycle(message, null, null, null)

      Try(cloudRunService.getService).toOption.fold {
        val request: String = CloudRun.json2yaml(cloudRunService.service)
        log(s"Creating new service:\n$request\n")
        val response: String = CloudRun.json2yaml(cloudRunService.createService)
        log(s"Response:\n$response")
      } { _ /*previous*/ =>
        val request: String = CloudRun.json2yaml(cloudRunService.service)
        log(s"Replacing existing service:\n$request\n")
        val response: String = CloudRun.json2yaml(cloudRunService.replaceService)
        log(s"Response:\n$response")
      }
    }
  }

  class GetServiceYamlTask extends ServiceTask(
    description = "Get the service YAML from Google Cloud Run",
    group = "help"
  ) {
    override protected def execute(cloudRunService: CloudRunService): Unit =
      getProject.getLogger.lifecycle(CloudRun.json2yaml(cloudRunService.getService))
  }

  class GetLatestRevisionYamlTask extends ServiceTask(
    description = "Get the latest revision YAML from Google Cloud Run",
    group = "help"
  ) {
    override protected def execute(cloudRunService: CloudRunService): Unit =
      getProject.getLogger.lifecycle(CloudRun.json2yaml(cloudRunService.getLatestRevision))
  }
}
