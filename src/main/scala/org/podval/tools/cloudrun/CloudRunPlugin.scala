package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.Service
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.api.provider.{ListProperty, Property}
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.api.{DefaultTask, Plugin, Project}
import org.gradle.process.ExecSpec
import scala.jdk.CollectionConverters.{ListHasAsScala, SeqHasAsJava}
import javax.inject.Inject

final class CloudRunPlugin extends Plugin[Project]:

  def apply(project: Project): Unit =
    project.getExtensions.create("cloudRun", classOf[CloudRunPlugin.Extension])

    project.getTasks.create("cloudRunDeploy"          , classOf[CloudRunPlugin.DeployTask          ])
    project.getTasks.create("cloudRunLocal"           , classOf[CloudRunPlugin.RunLocalTask        ])
    project.getTasks.create("cloudRunDescribe"        , classOf[CloudRunPlugin.DescribeTask        ])
    project.getTasks.create("cloudRunDescribeRevision", classOf[CloudRunPlugin.DescribeRevisionTask])

object CloudRunPlugin:
  private val serviceAccountKeyPropertyDefault: String = "gcloudServiceAccountKey"

  // Task and extension classes are not final so that Gradle could create decorated instances.

  class DeployTask extends DefaultTask:
    setDescription("Deploy the service to Google Cloud Run")
    setGroup("publishing")
    dependsOnJibTask(this, "jib")
    @TaskAction final def execute(): Unit = getExtension(this).cloudRunService.deploy()

  class RunLocalTask extends DefaultTask:
    setDescription("Run Cloud Run service in the local Docker")
    setGroup("publishing")
    dependsOnJibTask(this, "jibDockerBuild")
    private val additionalOptions: ListProperty[String] = getProject.getObjects.listProperty(classOf[String])
    @Input def getAdditionalOptions: ListProperty[String] = additionalOptions
    additionalOptions.set(Seq.empty[String].asJava)
    @TaskAction final def execute(): Unit =
      val commandLine: Seq[String] = CloudRunService.localDockerCommandLine(
        getExtension(this).service,
        additionalOptions.get.asScala.toSeq
      )
      getLogger.lifecycle(s"Running: ${commandLine.mkString(" ")}")
      getProject.exec((execSpec: ExecSpec) => execSpec.setCommandLine(commandLine.asJava))

  class DescribeTask extends DefaultTask:
    setDescription("Get the Service YAML from Google Cloud Run")
    setGroup("help")
    @TaskAction final def execute(): Unit =
      getProject.getLogger.lifecycle("Latest Service YAML:\n" +
        Util.yamlObjectMapper.writeValueAsString(getExtension(this).cloudRunService.get))

  class DescribeRevisionTask extends DefaultTask:
    setDescription("Get the latest Revision YAML from Google Cloud Run")
    setGroup("help")
    @TaskAction final def execute(): Unit =
      getProject.getLogger.lifecycle("Latest Revision YAML:\n" +
        Util.yamlObjectMapper.writeValueAsString(getExtension(this).cloudRunService.getLatestRevision))

  private def dependsOnJibTask(task: DefaultTask, jibTaskName: String): Unit = task.getProject.afterEvaluate((project: Project) =>
    if getJib(project).isDefined then task.dependsOn(project.getTasks.getByPath(jibTaskName))
  )

  // Extension with the name 'jib' comes from the [JIB plugin](https://github.com/GoogleContainerTools/jib);
  // if it exists, tasks 'jib' and 'jibDockerBuild' exist too.
  private def getJib(project: Project): Option[JibExtension] = Option(project.getExtensions.findByType(classOf[JibExtension]))

  private def getValue(property: Property[String], default: Option[String], name: String): String =
    val fromProperty: Option[String] = if !property.isPresent then None else Some(property.get)
    val result: Option[String] = fromProperty.orElse(default)
    if result.isEmpty then throw IllegalArgumentException(s"$name is not set!")
    result.get



  private def getExtension(task: DefaultTask): Extension =
    task.getProject.getExtensions.getByType(classOf[Extension])

  abstract class Extension @Inject(project: Project):
    def getServiceAccountKeyProperty: Property[String]
    def getServiceYamlFilePath: Property[String]
    def getRegion: Property[String]
    private def getRegionValue: String = getValue(
      property = getRegion,
      default = None,
      name = "region"
    )

    project.afterEvaluate { (project: Project) =>
      // verify that the extension is configured properly, initialize derived values and configure JIB
      getRegionValue
      serviceAccountKey
      service

      getJib(project).foreach { jibExtension =>
        def configure(
          name: String,
          getter: JibExtension => String,
          setter: (JibExtension, String) => Unit,
          value: String
        ): Unit = if getter(jibExtension) == null then
          setter(jibExtension, value)
          project.getLogger.info(s"CloudRun: set '$name' to '$value'.", null, null, null)

        configure("jib.to.image",
          _.getTo.getImage, _.getTo.setImage(_), CloudRunService.containerImage(service))
        configure("jib.to.auth.username",
          _.getTo.getAuth.getUsername, _.getTo.getAuth.setUsername(_), "_json_key")
        serviceAccountKey.foreach(serviceAccountKey =>
          configure("jib.to.auth.password",
            _.getTo.getAuth.getPassword, _.getTo.getAuth.setPassword(_), serviceAccountKey))
      }
    }

    private lazy val serviceAccountKey: Option[String] =
      val keyProperty: String = getValue(
        property = getServiceAccountKeyProperty,
        default = Some(serviceAccountKeyPropertyDefault),
        name = "serviceAccountKeyProperty"
      )
      if keyProperty.startsWith("/") then
        val key: String = Util.file2string(keyProperty)
        project.getLogger.lifecycle(
          "CloudRun: Add the following property to your ~/.gradle/gradle.properties file:\n" +
            serviceAccountKeyPropertyDefault + "= \\\n" + // TODO what if the name is not default?
            key
              .replace("\n", " \\\n") // TODO order?
              .replace("\\n", "\\\\n")
        )
        Some(key)
      else Option(System.getenv(keyProperty))
        .orElse(Option(project.findProperty(keyProperty).asInstanceOf[String]))
        .orElse {
          project.getLogger.lifecycle(
            s"CloudRun: Service account key not defined (looked at environment variable and property '$keyProperty')."
          )
          None
        }

    lazy val service: Service =
      val service: Service = Util.readServiceYaml(
        getValue(
          property = getServiceYamlFilePath,
          default = Some(s"${project.getProjectDir}/service.yaml"),
          name = "serviceYamlFilePath"
        )
      )

      // verify that minimal configuration is present
      def verifyNotNull(getter: Service => AnyRef, what: String): Unit =
        require(getter(service) != null, s"$what is missing!")

      verifyNotNull(_.getMetadata, "metadata")
      verifyNotNull(_.getMetadata.getName, "metadata.name")
      verifyNotNull(_.getSpec, "spec")
      verifyNotNull(_.getSpec.getTemplate, "spec.template")
      verifyNotNull(_.getSpec.getTemplate.getSpec, "spec.template.spec")
      verifyNotNull(_.getSpec.getTemplate.getSpec.getContainers, "spec.template.spec.containers")
      verifyNotNull(_.getSpec.getTemplate.getSpec.getContainers.get(0), "spec.template.spec.containers(0)")
      verifyNotNull(_.getSpec.getTemplate.getSpec.getContainers.get(0).getImage, "spec.template.spec.containers(0).image")

      service

    def cloudRunService: CloudRunService =
      val credentials: ServiceAccountCredentials =
        CloudRunClient.credentials(serviceAccountKey.getOrElse(throw IllegalArgumentException("No service account key!")))

      val containerImage: String = CloudRunService.containerImage(service)
      val containerImageSegments: Array[String] = containerImage.split('/')
      require(containerImageSegments.length == 3)

      def verify(index: Int, expected: String, what: String): Unit =
        val value: String = containerImageSegments(index)
        require(value == expected, s"Unexpected $what in the image name $containerImage: $value instead of $expected")

      verify(0, "gcr.io", "image repository")
      verify(1, credentials.getProjectId, "project id")
      verify(2, CloudRunService.name(service), "service name")

      CloudRunService(
        CloudRunClient(credentials, getRegionValue),
        service,
        project.getLogger
      )


