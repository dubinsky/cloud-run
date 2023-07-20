package org.podval.tools.cloudrun

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.http.HttpCredentialsAdapter
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.services.run.v1.CloudRun as GoogleCloudRun
import com.google.api.services.run.v1.model.{Configuration, Revision, Route, Service, Status}
import scala.jdk.CollectionConverters.IterableHasAsScala

// Note: see https://github.com/googleapis/google-cloud-java
final class CloudRunClient(
  credentials: ServiceAccountCredentials,
  val region: String
):
  private val client: GoogleCloudRun = GoogleCloudRun.Builder(
    GoogleNetHttpTransport.newTrustedTransport,
    Util.jsonFactory,
    // Note: see https://github.com/googleapis/google-auth-library-java#using-credentials-with-google-http-client
    HttpCredentialsAdapter(credentials)
  )
    .setRootUrl(s"https://$region-run.googleapis.com")
    .setApplicationName(CloudRunClient.applicationName)
    .build()

  def projectId: String = credentials.getProjectId

  private def namespace: String = s"namespaces/$projectId"

  def listServices: List[Service] =
    getList(_.services().list(namespace))(_.getItems)

  def createService(service: Service): Service =
    get(_.services().create(namespace, service))

  def getService(serviceName: String): Service =
    get(_.services().get(s"$namespace/services/$serviceName"))

  def replaceService(service: Service): Service =
    get(_.services().replaceService(s"$namespace/services/${CloudRunService.name(service)}", service))

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

object CloudRunClient:
  private def getPackage: Package = getClass.getPackage
  
  val applicationName: String = getPackage.getName

  val applicationVersion: String = Option(getPackage.getImplementationVersion).getOrElse("unknown version")

