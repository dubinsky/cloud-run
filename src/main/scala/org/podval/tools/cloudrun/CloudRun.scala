package org.podval.tools.cloudrun

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.http.HttpCredentialsAdapter
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.services.run.v1.{CloudRunScopes, CloudRun as GoogleCloudRun}
import com.google.api.services.run.v1.model.{Configuration, Revision, Route, Service, Status}
import org.podval.tools.cloudrun.ServiceExtender.*
import org.slf4j.Logger

import scala.jdk.CollectionConverters.IterableHasAsScala

// Note: see https://github.com/googleapis/google-cloud-java
final class CloudRun(
  serviceAccountKey: String,
  val region: String,
  val log: Logger
):
  // Note: see https://github.com/googleapis/google-auth-library-java#google-auth-library-oauth2-http
  // (what is ServiceAccountJwtAccessCredentials.fromStream(keyStream) for?)
  private val credentials: ServiceAccountCredentials = ServiceAccountCredentials
    .fromStream(Util.string2stream(serviceAccountKey))
    .createScoped(CloudRunScopes.all)
    .asInstanceOf[ServiceAccountCredentials]

  private val client: GoogleCloudRun = GoogleCloudRun.Builder(
    GoogleNetHttpTransport.newTrustedTransport,
    Util.jsonFactory,
    // Note: see https://github.com/googleapis/google-auth-library-java#using-credentials-with-google-http-client
    HttpCredentialsAdapter(credentials)
  )
    .setRootUrl(s"https://$region-run.googleapis.com")
    .setApplicationName(CloudRun.applicationName)
    .build()

  def projectId: String = credentials.getProjectId

  private def namespace: String = "namespaces/" + projectId

  def listServices: List[Service] =
    getList(_.services().list(namespace))(_.getItems)

  def createService(service: Service): Service =
    get(_.services().create(namespace, service))

  def getService(serviceName: String): Service =
    get(_.services().get(s"$namespace/services/$serviceName"))

  def replaceService(service: Service): Service =
    get(_.services().replaceService(s"$namespace/services/${service.name}", service))

  def deleteService(serviceName: String): Status =
    get(_.services().delete(s"$namespace/services/$serviceName"))

  def listRevisions: List[Revision] =
    getList(_.revisions().list(namespace))(_.getItems)

  def getRevision(revisionName: String): Revision =
    get(_.revisions().get(s"$namespace/revisions/$revisionName"))

  def deleteRevision(revisionName: String): Status =
    get(_.revisions().delete(s"$namespace/revisions/$revisionName"))

  def listRoutes: List[Route] =
    getList(_.routes().list(namespace))(_.getItems)

  def getRoute(routeName: String): Route =
    get(_.routes().get(s"$namespace/routes/$routeName"))

  def listConfigurations: List[Configuration] =
    getList(_.configurations().list(namespace))(_.getItems)

  def getConfiguration(configurationName: String): Configuration =
    get(_.configurations().get(s"$namespace/configurations/$configurationName"))

  private def getList[R, T](
    request: GoogleCloudRun#Namespaces => AbstractGoogleClientRequest[R])(
    items: R => java.util.List[T]
  ): List[T] =
    items(get(request)).asScala.toList

  private def get[T](request: GoogleCloudRun#Namespaces => AbstractGoogleClientRequest[T]): T =
    request(client.namespaces()).execute()

object CloudRun:

  val applicationName: String = getClass.getPackage.getName

  val applicationVersion: String = Option(getClass.getPackage.getImplementationVersion).getOrElse("unknown version")
