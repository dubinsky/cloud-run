package org.podval.tools.cloudrun

import org.gradle.api.provider.{ListProperty, Property, Provider}
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project}
import com.google.api.services.run.v1.model.Service
import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.process.ExecSpec
import scala.jdk.CollectionConverters.{ListHasAsScala, SeqHasAsJava}

final class CloudRunPlugin extends Plugin[Project] {

  def apply(project: Project): Unit = {
    val extension = project.getExtensions.create(CloudRunPlugin.extensionName, classOf[CloudRunPlugin.Extension], project)

    val runLocalTask = project.getTasks.create("cloudRunLocal", classOf[CloudRunPlugin.RunLocalTask])
    val deployTask = project.getTasks.create("cloudRunDeploy", classOf[CloudRunPlugin.DeployTask])
    project.getTasks.create("cloudRunDescribe", classOf[CloudRunPlugin.DescribeTask])
    project.getTasks.create("cloudRunDescribeLatestRevision", classOf[CloudRunPlugin.DescribeLatestRevisionTask])

    // Extension with the name 'jib' is assumed to be created by the
    // [JIB plugin](https://github.com/GoogleContainerTools/jib);
    // it is then of the type com.google.cloud.tools.jib.gradle.JibExtension, and task 'jib' exists.
    project.afterEvaluate((project: Project) =>
      Option(project.getExtensions.findByName("jib")).map(_.asInstanceOf[JibExtension]).foreach { jibExtension =>
        runLocalTask.dependsOn(project.getTasks.findByPath("jibDockerBuild"))

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
          _.getTo.getImage           , _.getTo.setImage           (_), extension.getContainerImage)
        configure("jib.to.auth.username",
          _.getTo.getAuth.getUsername, _.getTo.getAuth.setUsername(_), value = "_json_key"        )
        configure("jib.to.auth.password",
          _.getTo.getAuth.getPassword, _.getTo.getAuth.setPassword(_), extension.serviceAccountKey)
      }
    )
  }
}

object CloudRunPlugin {

  private val extensionName: String = "cloudRun"

  private val serviceAccountKeyPropertyDefault: String = "gcloudServiceAccountKey"

  private def getExtension(project: Project): Extension =
    project.getExtensions.getByName(extensionName).asInstanceOf[Extension]

  // Extension and Task classes are not final so that Gradle could create decorated instances.

  class Extension(project: Project) {
    private val serviceYamlFilePath: Property[String] = project.getObjects.property(classOf[String])
    def getServiceYamlFilePath: Property[String] = serviceYamlFilePath
    serviceYamlFilePath.set(s"${project.getProjectDir}/service.yaml")

    private val region: Property[String] = project.getObjects.property(classOf[String])
    def getRegion: Property[String] = region

    private val serviceAccountKeyProperty: Property[String] = project.getObjects.property(classOf[String])
    def getServiceAccountKeyProperty: Property[String] = serviceAccountKeyProperty
    serviceAccountKeyProperty.set(serviceAccountKeyPropertyDefault)

    private val service: Service =
      CloudRun.yaml2service(getValue(serviceYamlFilePath, "serviceYamlFilePath"))

    def getServiceName   : String = CloudRun.getServiceName   (service)
    def getContainerImage: String = CloudRun.getContainerImage(service)
    def getCpu           : Float  = CloudRun.getCpu           (service)
    def getMemory        : String = CloudRun.getMemory        (service)

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

    def cloudRunService: CloudRunService = new CloudRunService(
      run = new CloudRun(
        serviceAccountKey,
        region = getValue(region, "region"),
        log = (message: String) => project.getLogger.warn(message)
      ),
      service = service
    )

    private def getValue(property: Property[String], name: String): String = {
      val result: String = property.get()
      if (result.isEmpty) throw new IllegalArgumentException(s"$name is not set!")
      result
    }
  }

  class DeployTask extends DefaultTask {
    setDescription("Deploy the service to Google Cloud Run")
    setGroup("publishing")

    @TaskAction final def execute(): Unit = getExtension(getProject).cloudRunService.deploy()
  }

  class DescribeTask extends DefaultTask {
    setDescription("Get the Service YAML from Google Cloud Run")
    setGroup("help")

    @TaskAction final def execute(): Unit = getExtension(getProject).cloudRunService.describe()
  }

  class DescribeLatestRevisionTask extends DefaultTask {
    setDescription("Get the latest Revision YAML from Google Cloud Run")
    setGroup("help")

    @TaskAction final def execute(): Unit = getExtension(getProject).cloudRunService.describeLatestRevision()
  }

  class RunLocalTask extends DefaultTask {
    setDescription("Run Cloud Run service in the local Docker")
    setGroup("publishing")

    private val port: Property[Integer] = getProject.getObjects.property(classOf[Integer])
    @Input def getPort: Property[Integer] = port
    port.set(8080)

    private val additionalOptions: ListProperty[String] = getProject.getObjects.listProperty(classOf[String])
    @Input def getAdditionalOptions: ListProperty[String] = additionalOptions
    additionalOptions.set(Seq.empty[String].asJava)

    @TaskAction final def execute(): Unit = {
      val extension: Extension = getExtension(getProject)
      val commandLine: Seq[String] = List(
        "docker"   ,
        "run"      ,
        "--name"   , extension.getServiceName,
        "--rm"     ,
        "--cpus"   , extension.getCpu.toString,
        "--memory" , extension.getMemory,
        "--env"    , "PORT=8080",
        "--publish", port.get().toString + ":8080"
      ) ++ additionalOptions.get.asScala ++  Seq(
        extension.getContainerImage
      )
      getLogger.lifecycle(s"Running: ${commandLine.mkString(" ")}")
      getProject.exec((execSpec: ExecSpec) => execSpec.setCommandLine(commandLine.asJava))
    }
  }
}
