package org.podval.tools.cloudrun

import java.io.{ByteArrayInputStream, File, FileInputStream, InputStream}
import java.nio.charset.Charset
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.{YAMLFactory, YAMLGenerator}
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.http.HttpCredentialsAdapter
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.{JsonFactory, JsonObjectParser}
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.run.v1.{CloudRunScopes, CloudRun => GoogleCloudRun}
import com.google.api.services.run.v1.model.{Revision, Service}
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

  private def projectId: String = credentials.getProjectId

  def listServices: List[Service] = client
    .namespaces().services().list(s"namespaces/$projectId")
    .execute().getItems.asScala.toList

  def getService(serviceName: String): Service = client
    .namespaces().services().get(s"namespaces/$projectId/services/$serviceName")
    .execute()

  def listRevisions: List[Revision] = client
    .namespaces().revisions().list(s"namespaces/$projectId")
    .execute().getItems.asScala.toList

  def getRevision(revisionName: String): Revision = client
    .namespaces().revisions().get(s"namespaces/$projectId/revisions/$revisionName")
    .execute()

  def createService(service: Service): Service = client
    .namespaces().services().create(s"namespaces/$projectId", service)
    .execute()

  def replaceService(serviceName: String, service: Service): Service = client
    .namespaces().services().replaceService(s"namespaces/$projectId/services/$serviceName", service)
    .execute()
}

object CloudRun {

  final class ForService(run: CloudRun, serviceYamlFilePath: String) {

    private val service: Service = new JsonObjectParser(jsonFactory).parseAndClose(
      string2stream(yaml2json(serviceYamlFilePath)),
      utf8,
      classOf[Service]
    )

    private def serviceName: String = service.getMetadata.getName

    // container image name of the first container!
    def containerImage: String = service.getSpec.getTemplate.getSpec.getContainers.get(0).getImage

    def getService: Service = run.getService(serviceName)

    // equivalent to `gcloud run services describe $serviceName --format export`
    def getServiceYaml: String = json2yaml(getService)

    def deploy: Service = (try Some(getService) catch { case _: GoogleJsonResponseException => None })
      .fold(run.createService(service))(_ /*previous*/ => run.replaceService(serviceName, service))

    def getLatestRevisionYaml: String =
      json2yaml(run.getRevision(getService.getStatus.getLatestCreatedRevisionName))
  }

  private val applicationName: String = "podval-google-cloud-run"

  private def utf8: Charset = Charset.forName("UTF-8")

  private def jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance

  private def yaml2json(yamlFilePath: String): String = {
    val yamlInputStream: InputStream = new FileInputStream(new File(yamlFilePath))
    val objectMapper: ObjectMapper = new ObjectMapper(new YAMLFactory)
    objectMapper.readTree(yamlInputStream).toString
  }

  private def json2yaml(value: AnyRef): String = {
    val yamlFactory: YAMLFactory = new YAMLFactory
    yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER) // suppress leading "---"
    new ObjectMapper(yamlFactory).writeValueAsString(value)
  }

  def file2string(path: String): String = {
    val source: Source = Source.fromFile(path)
    val result: String = source.getLines.mkString("\n")
    source.close()
    result
  }

  private def string2stream(string: String): InputStream =
    new ByteArrayInputStream(string.getBytes(utf8))
}
