package org.podval.tools.cloudrun

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.Charset
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.{YAMLFactory, YAMLGenerator}
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.http.HttpCredentialsAdapter
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.{JsonFactory, JsonObjectParser}
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.run.v1.{CloudRunScopes, CloudRun => GoogleCloudRun}
import com.google.api.services.run.v1.model.{Revision, Service, Status}
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.io.Source

final class CloudRun(
  serviceAccountKey: String,
  region: String
) {
  private val credentials: ServiceAccountCredentials = ServiceAccountCredentials
    .fromStream(CloudRun.string2stream(serviceAccountKey))
    .createScoped(CloudRunScopes.all)
    .asInstanceOf[ServiceAccountCredentials]

  private val client: GoogleCloudRun = new GoogleCloudRun.Builder(
    GoogleNetHttpTransport.newTrustedTransport,
    CloudRun.jsonFactory,
    new HttpCredentialsAdapter(credentials)
  )
    .setRootUrl(s"https://$region-run.googleapis.com")
    .setApplicationName(CloudRun.applicationName)
    .build()

  private def namespace: String = "namespaces/" + credentials.getProjectId

  def listServices: List[Service] = client
    .namespaces().services().list(namespace)
    .execute().getItems.asScala.toList

  def createService(service: Service): Service = client
    .namespaces().services().create(namespace, service)
    .execute()

  def getService(serviceName: String): Service = client
    .namespaces().services().get(s"$namespace/services/$serviceName")
    .execute()

  def replaceService(serviceName: String, service: Service): Service = client
    .namespaces().services().replaceService(s"$namespace/services/$serviceName", service)
    .execute()

  def deleteService(serviceName: String): Status = client
    .namespaces().services().delete(s"$namespace/services/$serviceName")
    .execute()

  // TODO introduce CloudRunRevision and add appropriate methods to CloudRunService (using queries)

  def listRevisions: List[Revision] = client
    .namespaces().revisions().list(namespace)
    .execute().getItems.asScala.toList

  def getRevision(revisionName: String): Revision = client
    .namespaces().revisions().get(s"$namespace/revisions/$revisionName")
    .execute()

  def deleteRevision(revisionName: String): Status = client
    .namespaces().revisions().delete(s"$namespace/revisions/$revisionName")
    .execute()
}

object CloudRun {

  private val applicationName: String = "podval-google-cloud-run"

  private def utf8: Charset = Charset.forName("UTF-8")

  private def jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance

  private def yamlObjectMapper: ObjectMapper = {
    val yamlFactory: YAMLFactory = new YAMLFactory
    yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER) // suppress leading "---"
    new ObjectMapper(yamlFactory)
  }

  def yaml2json(yamlFilePath: String): String = yamlObjectMapper
    .readTree(file2string(yamlFilePath))
    .toString

  def json2yaml(value: AnyRef): String = yamlObjectMapper
    .writeValueAsString(value)

  def json2object[T](clazz: Class[T], json: String): T = new JsonObjectParser(jsonFactory).parseAndClose(
    string2stream(json),
    utf8,
    clazz
  )

  def file2string(path: String): String = {
    val source: Source = Source.fromFile(path)
    val result: String = source.getLines.mkString("\n")
    source.close()
    result
  }

  private def string2stream(string: String): InputStream =
    new ByteArrayInputStream(string.getBytes(utf8))
}
