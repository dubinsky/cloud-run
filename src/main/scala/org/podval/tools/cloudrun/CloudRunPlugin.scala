package org.podval.tools.cloudrun

import org.gradle.api.provider.{Property, Provider}
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project}
import com.google.cloud.tools.jib.gradle.{AuthParameters, JibExtension, TargetImageParameters}

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

    wireTask("cloudRunDescribe"              , classOf[CloudRunPlugin.DescribeTask              ])
    wireTask("cloudRunDescribeLatestRevision", classOf[CloudRunPlugin.DescribeLatestRevisionTask])

    // Extension with the name 'jib' is assumed to be created by the
    // [JIB plugin](https://github.com/GoogleContainerTools/jib);
    // it is then of the type com.google.cloud.tools.jib.gradle.JibExtension, and task 'jib' exists.
    project.afterEvaluate((project: Project) =>
      Option(project.getExtensions.findByName("jib")).map(_.asInstanceOf[JibExtension]).foreach { jibExtension =>
        deployTask.dependsOn(project.getTasks.findByPath("jib"))

        // Note: both getter and setter are needed since JIB doesn't expose the properties themselves.
        def configure(
          name: String,
          getter: JibExtension => String,
          setter: (JibExtension, Provider[String]) => Unit,
          value: => String
        ): Unit = if (getter(jibExtension) == null) {
          setter(jibExtension, project.provider(() => value))
          project.getLogger.info(s"CloudRun: configured '$name'.", null, null, null)
        }

        configure("jib.to.image"        ,
          _.getTo.getImage           , _.getTo.setImage           (_), extension.service.containerImage)
        configure("jib.to.auth.username",
          _.getTo.getAuth.getUsername, _.getTo.getAuth.setUsername(_), value = "_json_key"             )
        configure("jib.to.auth.password",
          _.getTo.getAuth.getPassword, _.getTo.getAuth.setPassword(_), extension.serviceAccountKey     )
      }
    )
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

    lazy val service: CloudRunService = new CloudRun(
      serviceAccountKey,
      region = getValue(region, "region"),
      log = (message: String) => project.getLogger.warn(message)
    ).serviceForYaml(getValue(serviceYamlFilePath, "serviceYamlFilePath"))

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
    group: String,
    action: CloudRunService => Unit
  ) extends DefaultTask {
    setDescription(description)
    setGroup(group)

    private val cloudRunService: Property[CloudRunService] =
      getProject.getObjects.property(classOf[CloudRunService])

    @Input def getCloudRunService: Property[CloudRunService] = cloudRunService

    @TaskAction final def execute(): Unit = action(cloudRunService.get)
  }

  class DeployTask extends ServiceTask(
    description = "Deploy the service to Google Cloud Run",
    group = "publishing",
    action = _.deploy()
  )

  class DescribeTask extends ServiceTask(
    description = "Get the Service YAML from Google Cloud Run",
    group = "help",
    action = _.describe()
  )

  class DescribeLatestRevisionTask extends ServiceTask(
    description = "Get the latest Revision YAML from Google Cloud Run",
    group = "help",
    action = _.describeLatestRevision()
  )
}
