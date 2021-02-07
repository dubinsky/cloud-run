package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.Service
import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.podval.tools.cloudrun.ServiceExtender.Operations

// Extension class is not final so that Gradle could create decorated instances.

class CloudRunExtension(project: Project) {
  private val serviceYamlFilePath: Property[String] = project.getObjects.property(classOf[String])
  def getServiceYamlFilePath: Property[String] = serviceYamlFilePath
  serviceYamlFilePath.set(s"${project.getProjectDir}/service.yaml")

  private val region: Property[String] = project.getObjects.property(classOf[String])
  def getRegion: Property[String] = region

  private val serviceAccountKeyProperty: Property[String] = project.getObjects.property(classOf[String])
  def getServiceAccountKeyProperty: Property[String] = serviceAccountKeyProperty
  serviceAccountKeyProperty.set(CloudRunExtension.serviceAccountKeyPropertyDefault)

  project.afterEvaluate { (project: Project) =>
    // verify that the extension is configured properly, initialize derived values and configure JIB
    getRegionValue

    service
    serviceAccountKey

    CloudRunPlugin.getJib(project).foreach { jibExtension =>
      def configure(
        name: String,
        getter: JibExtension => String,
        setter: (JibExtension, String) => Unit,
        value: String
      ): Unit = if (getter(jibExtension) == null) {
        setter(jibExtension, value)
        project.getLogger.info(s"CloudRun: set '$name' to '$value'.", null, null, null)
      }

      configure("jib.to.image",
        _.getTo.getImage, _.getTo.setImage(_), service.containerImage)
      configure("jib.to.auth.username",
        _.getTo.getAuth.getUsername, _.getTo.getAuth.setUsername(_), value = "_json_key")
      serviceAccountKey.foreach(serviceAccountKey =>
        configure("jib.to.auth.password",
          _.getTo.getAuth.getPassword, _.getTo.getAuth.setPassword(_), serviceAccountKey))
    }
  }

  lazy val service: Service = CloudRun.yamlFile2[Service](
    clazz = classOf[Service],
    yamlFilePath = CloudRunExtension.getValue(serviceYamlFilePath, "serviceYamlFilePath")
  )

  private lazy val serviceAccountKey: Option[String] = {
    val keyProperty: String = CloudRunExtension.getValue(serviceAccountKeyProperty, "serviceAccountKeyProperty")
    if (keyProperty.startsWith("/")) {
      val key: String = CloudRun.file2string(keyProperty)
      project.getLogger.lifecycle(
        "CloudRun: Add the following property to your ~/.gradle/gradle.properties file:\n" +
          CloudRunExtension.serviceAccountKeyPropertyDefault  + "= \\\n" +
          key
            .replace("\n", " \\\n")
            .replace("\\n", "\\\\n")
      )
      Some(key)
    } else Option(System.getenv(keyProperty))
      .orElse(Option(project.findProperty(keyProperty).asInstanceOf[String]))
      .orElse {
        project.getLogger.lifecycle(
          "CloudRun: Service account key not defined" +
            s" (looked at environment variable and property '$keyProperty')."
        )
        None
      }
  }

  private def getRegionValue: String = CloudRunExtension.getValue(region, "region")
}

object CloudRunExtension {
  val extensionName: String = "cloudRun"

  private val serviceAccountKeyPropertyDefault: String = "gcloudServiceAccountKey"

  def get(project: Project): CloudRunExtension =
    project.getExtensions.getByName(extensionName).asInstanceOf[CloudRunExtension]

  def getService(project: Project): CloudRunService = {
    val extension: CloudRunExtension = get(project)
    new CloudRunService(
      run = new CloudRun(
        serviceAccountKey = extension.serviceAccountKey.getOrElse(throw new IllegalArgumentException("No service account key!")),
        region = extension.getRegionValue,
        log = project.getLogger
      ),
      service = extension.service
    )
  }

  private def getValue(property: Property[String], name: String): String = {
    val result: String = property.get()
    if (result.isEmpty) throw new IllegalArgumentException(s"$name is not set!")
    result
  }
}