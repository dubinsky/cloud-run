package org.podval.tools.cloudrun

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.{YAMLFactory, YAMLGenerator}
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.http.HttpCredentialsAdapter
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.json.{JsonFactory, JsonObjectParser}
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.run.v1.{CloudRunScopes, CloudRun => GoogleCloudRun}
import com.google.api.services.run.v1.model.{Configuration, Revision, Route, Service, Status}
import org.podval.tools.cloudrun.ServiceExtender.Operations
import org.slf4j.Logger
import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.Charset
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.io.Source

// Note: see https://github.com/googleapis/google-cloud-java
final class CloudRun(
  serviceAccountKey: String,
  val region: String,
  val log: Logger
) {
  // Note: see https://github.com/googleapis/google-auth-library-java#google-auth-library-oauth2-http
  // (what is ServiceAccountJwtAccessCredentials.fromStream(keyStream) for?)
  private val credentials: ServiceAccountCredentials = ServiceAccountCredentials
    .fromStream(CloudRun.string2stream(serviceAccountKey))
    .createScoped(CloudRunScopes.all)
    .asInstanceOf[ServiceAccountCredentials]

  private val client: GoogleCloudRun = new GoogleCloudRun.Builder(
    GoogleNetHttpTransport.newTrustedTransport,
    CloudRun.jsonFactory,
    // Note: see https://github.com/googleapis/google-auth-library-java#using-credentials-with-google-http-client
    new HttpCredentialsAdapter(credentials)
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
}

object CloudRun {

  val applicationName: String = getClass.getPackage.getName

  val applicationVersion: String = Option(getClass.getPackage.getImplementationVersion).getOrElse("unknown version")

  private def jsonFactory: JsonFactory = GsonFactory.getDefaultInstance

  // Note: when parsing Service YAML, objectMapper.readValue(inputStream, classOf[Service]) throws
  //   java.lang.IllegalArgumentException:
  //   Can not set com.google.api.services.run.v1.model.ObjectMeta field
  //   com.google.api.services.run.v1.model.Service.metadata to java.util.LinkedHashMap
  // so I convert YAML into a JSON string and then parse it using Google's parser:
  def yamlFile2[T](clazz: Class[T], yamlFilePath: String): T = {
    val json: String = yamlObjectMapper
      .readTree(file2string(yamlFilePath))
      .toString

    new JsonObjectParser(jsonFactory).parseAndClose(
      string2stream(json),
      utf8,
      clazz
    )
  }

  def json2yaml(value: AnyRef): String = yamlObjectMapper.writeValueAsString(value)

  private def yamlObjectMapper: ObjectMapper = {
    val yamlFactory: YAMLFactory = new YAMLFactory
    yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER) // suppress leading "---"
    new ObjectMapper(yamlFactory)
  }

  def file2string(path: String): String = source2string(Source.fromFile(path))

  // def stream2string(stream: InputStream): String = source2string(Source.fromInputStream(stream)(new Codec(utf8)))

  private def source2string(source: Source): String = {
    val result: String = source.getLines().mkString("\n")
    source.close()
    result
  }

  private def string2stream(string: String): InputStream = new ByteArrayInputStream(string.getBytes(utf8))

  private def utf8: Charset = Charset.forName("UTF-8")
}
