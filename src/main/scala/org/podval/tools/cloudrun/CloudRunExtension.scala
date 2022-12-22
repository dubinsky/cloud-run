package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.Service
import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.api.Project
import org.gradle.api.provider.Property
import ServiceExtender.*
import javax.inject.Inject

// Extension class is not final so that Gradle could create decorated instances.

abstract class CloudRunExtension @Inject(project: Project):
  def getServiceYamlFilePath: Property[String]
  def getRegion: Property[String]
  def getServiceAccountKeyProperty: Property[String]

  project.afterEvaluate { (project: Project) =>
    // verify that the extension is configured properly, initialize derived values and configure JIB
    getRegionValue

    service
    serviceAccountKey

    Util.getJib(project).foreach { jibExtension =>
      def configure(
        name: String,
        getter: JibExtension => String,
        setter: (JibExtension, String) => Unit,
        value: String
      ): Unit = if getter(jibExtension) == null then
        setter(jibExtension, value)
        project.getLogger.info(s"CloudRun: set '$name' to '$value'.", null, null, null)

      configure("jib.to.image",
        _.getTo.getImage, _.getTo.setImage(_), service.containerImage)
      configure("jib.to.auth.username",
        _.getTo.getAuth.getUsername, _.getTo.getAuth.setUsername(_), "_json_key")
      serviceAccountKey.foreach(serviceAccountKey =>
        configure("jib.to.auth.password",
          _.getTo.getAuth.getPassword, _.getTo.getAuth.setPassword(_), serviceAccountKey))
    }
  }

  lazy val service: Service = Util.readServiceYaml(
    CloudRunExtension.getValue(
      property = getServiceYamlFilePath,
      default = Some(s"${project.getProjectDir}/service.yaml"),
      name = "serviceYamlFilePath"
    )
  )

  lazy val serviceAccountKey: Option[String] =
    val keyProperty: String = CloudRunExtension.getValue(
      property = getServiceAccountKeyProperty,
      default = Some(CloudRunExtension.serviceAccountKeyPropertyDefault),
      name = "serviceAccountKeyProperty"
    )
    if keyProperty.startsWith("/") then
      val key: String = Util.file2string(keyProperty)
      project.getLogger.lifecycle(
        "CloudRun: Add the following property to your ~/.gradle/gradle.properties file:\n" +
          CloudRunExtension.serviceAccountKeyPropertyDefault  + "= \\\n" +
          key
            .replace("\n", " \\\n") // TODO order?
            .replace("\\n", "\\\\n")
      )
      Some(key)
    else Option(System.getenv(keyProperty))
      .orElse(Option(project.findProperty(keyProperty).asInstanceOf[String]))
      .orElse {
        project.getLogger.lifecycle(
          "CloudRun: Service account key not defined" +
            s" (looked at environment variable and property '$keyProperty')."
        )
        None
      }

  def getRegionValue: String = CloudRunExtension.getValue(
    property = getRegion,
    default = None,
    name = "region"
  )

object CloudRunExtension:
  
  def getValue(property: Property[String], default: Option[String], name: String): String =
    val fromProperty: Option[String] = if !property.isPresent then None else Some(property.get)
    val result: Option[String] = fromProperty.orElse(default)
    if result.isEmpty then throw IllegalArgumentException(s"$name is not set!")
    result.get

  val serviceAccountKeyPropertyDefault: String = "gcloudServiceAccountKey"
