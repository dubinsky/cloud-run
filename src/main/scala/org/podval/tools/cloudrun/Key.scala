package org.podval.tools.cloudrun

import com.google.api.services.run.v1.CloudRunScopes
import com.google.auth.oauth2.ServiceAccountCredentials
import org.gradle.api.Project

object Key:
  val serviceAccountKeyPropertyDefault: String = "gcloudServiceAccountKey"

  def get(
    keyProperty: String,
    project: Project
  ): Option[String] =
    if keyProperty.startsWith("/") then
      val key: String = Util.file2string(keyProperty)
      project.getLogger.lifecycle(
        "CloudRun: add the following property to your ~/.gradle/gradle.properties file or set an environment variable:\n" +
        keyProperty + "= \\\n" +
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

  // Note: see https://github.com/googleapis/google-auth-library-java#google-auth-library-oauth2-http
  // (what is ServiceAccountJwtAccessCredentials.fromStream(keyStream) for?)
  def credentials(serviceAccountKey: Option[String]): ServiceAccountCredentials = ServiceAccountCredentials
    .fromStream(Util.string2stream(serviceAccountKey.getOrElse(throw IllegalArgumentException("No service account key!"))))
    .createScoped(CloudRunScopes.all)
    .asInstanceOf[ServiceAccountCredentials]
  