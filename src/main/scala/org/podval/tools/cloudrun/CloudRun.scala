package org.podval.tools.cloudrun

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.{YAMLFactory, YAMLGenerator}
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.http.HttpCredentialsAdapter
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.{JsonFactory, JsonObjectParser}
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.run.v1.{CloudRunScopes, CloudRun => GoogleCloudRun}
import com.google.api.services.run.v1.model.{Configuration, Container, Revision, Route, Service, Status}
import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.Charset
import java.math.{MathContext, RoundingMode}
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.io.{Codec, Source}

final class CloudRun(
  serviceAccountKey: String,
  val region: String,
  val log: String => Unit
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

  def projectId: String = credentials.getProjectId

  private def namespace: String = "namespaces/" + projectId

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

  def listRevisions: List[Revision] = client
    .namespaces().revisions().list(namespace)
    .execute().getItems.asScala.toList

  def getRevision(revisionName: String): Revision = client
    .namespaces().revisions().get(s"$namespace/revisions/$revisionName")
    .execute()

  def deleteRevision(revisionName: String): Status = client
    .namespaces().revisions().delete(s"$namespace/revisions/$revisionName")
    .execute()

  def listRoutes: List[Route] = client
    .namespaces().routes().list(namespace)
    .execute().getItems.asScala.toList

  def getRoute(routeName: String): Route = client
    .namespaces().routes().get(s"$namespace/routes/$routeName")
    .execute()

  def listConfigurations: List[Configuration] = client
    .namespaces().configurations().list(namespace)
    .execute().getItems.asScala.toList

  def getConfiguration(configurationName: String): Configuration = client
    .namespaces().configurations().get(s"$namespace/configurations/$configurationName")
    .execute()
}

object CloudRun {

  val applicationName: String = getClass.getPackage.getName

  val applicationVersion: String = Option(getClass.getPackage.getImplementationVersion).getOrElse("unknown version")

  def getServiceName(service: Service): String = service.getMetadata.getName

  def getContainerImage(service: Service): String = getFirstContainer(service).getImage

  private val mathContext: MathContext = new MathContext(3, RoundingMode.HALF_UP)

  def getCpu(service: Service): Float = getResourceLimit(service, "cpu").fold(1.0f) { cpuStr =>
    val result: Double = if (cpuStr.endsWith("m")) cpuStr.init.toFloat / 1000.0 else cpuStr.toFloat
    BigDecimal(result, mathContext).floatValue
  }

  def getMemory(service: Service): String = getResourceLimit(service, "memory").get

  def getResourceLimit(service: Service, name: String): Option[String] =
    Option(getFirstContainer(service).getResources.getLimits.get(name))

  def getFirstContainer(service: Service): Container =
    service.getSpec.getTemplate.getSpec.getContainers.get(0)

  private def utf8: Charset = Charset.forName("UTF-8")

  private def jsonFactory: JsonFactory = GsonFactory.getDefaultInstance

  private def yamlObjectMapper: ObjectMapper = {
    val yamlFactory: YAMLFactory = new YAMLFactory
    yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER) // suppress leading "---"
    new ObjectMapper(yamlFactory)
  }

  def yaml2service(serviceYamlFilePath: String): Service =
    json2object(classOf[Service], yaml2json(serviceYamlFilePath))

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

  def file2string(path: String): String = source2string(Source.fromFile(path))

  def stream2string(stream: InputStream): String = source2string(Source.fromInputStream(stream)(new Codec(utf8)))

  private def source2string(source: Source): String = {
    val result: String = source.getLines().mkString("\n")
    source.close()
    result
  }

  private def string2stream(string: String): InputStream = new ByteArrayInputStream(string.getBytes(utf8))
}
