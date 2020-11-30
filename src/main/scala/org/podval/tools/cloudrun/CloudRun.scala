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

// inspired in part by the deploy-cloudrun GitHub Action
//   see https://github.com/google-github-actions/deploy-cloudrun
final class CloudRun(
  val serviceAccountKey: String,
  val region: String
) {
  val credentials: ServiceAccountCredentials = CloudRun.key2credentials(serviceAccountKey)

  // using credentials with Google's HTTP clients - see
  //   https://github.com/googleapis/google-auth-library-java#using-credentials-with-google-http-client
  private val client: GoogleCloudRun = new GoogleCloudRun.Builder(
    GoogleNetHttpTransport.newTrustedTransport,
    CloudRun.jsonFactory,
    new HttpCredentialsAdapter(credentials)
  )
    .setRootUrl(s"https://$region-run.googleapis.com")
    .setApplicationName(CloudRun.applicationName)
    .build()

  def projectId: String = credentials.getProjectId

  def listServices: List[Service] = client
    .namespaces().services().list(s"namespaces/$projectId")
    .execute().getItems.asScala.toList

  def listServiceNames: List[String] =
    listServices.map(CloudRun.getServiceName)

  def getService(serviceName: String): Service = client
    .namespaces().services().get(s"namespaces/$projectId/services/$serviceName")
    .execute()

  def getServiceIfExists(serviceName: String): Option[Service] =
    try { Some(getService(serviceName)) } catch { case _: GoogleJsonResponseException => None }

  // equivalent to `gcloud run services describe $serviceName --format export`
  def getServiceYaml(serviceName: String): String = CloudRun.json2yaml(getService(serviceName))

  def deployService(service: Service): Service =
    deployService(getServiceIfExists(CloudRun.getServiceName(service)), service)

  def deployService(previous: Option[Service], service: Service): Service =
    previous.fold(createService(service))(previous => replaceService(previous, service))

  def createService(service: Service): Service = client
    .namespaces().services().create(s"namespaces/$projectId", service)
    .execute()

  def replaceService(previous: Service, service: Service): Service = {
    // from the replaceService() JavaDoc:
    //  Only the spec and metadata labels and annotations are modifiable.
    // see https://github.com/google-github-actions/deploy-cloudrun/blob/e6563531efecd65332243ad924e3dcf72681c41a/src/service.ts#L138
    // GitHub Action merge 'previous' into 'service':
    //   spec.template.metadata.labels;
    //   spec.template.metadata.annotations;
    //   spec.template.spec.containers[0]:
    //     command and args are removed if not present in 'service'
    //     in env, variables present in 'previous' but not in 'service' are added;
    replaceService(service)
  }

  def replaceService(service: Service): Service =
    replaceService(CloudRun.getServiceName(service), service)

  def replaceService(serviceName: String, service: Service): Service = client
    .namespaces().services().replaceService(s"namespaces/$projectId/services/$serviceName", service)
    .execute()

  def listRevisions: List[Revision] = client
    .namespaces().revisions().list(s"namespaces/$projectId")
    .execute().getItems.asScala.toList

  def getRevision(revisionName: String): Revision = client
    .namespaces().revisions().get(s"namespaces/$projectId/revisions/$revisionName")
    .execute()

  def getLatestRevision(serviceName: String): Revision =
    getRevision(getService(serviceName).getStatus.getLatestCreatedRevisionName)

  def getLatestRevisionYaml(serviceName: String): String =
    CloudRun.json2yaml(getLatestRevision(serviceName))

  // used by the GitHub Action to determine if the service exists; I do it differently.
  def exists(serviceName: String): Boolean =
    listServiceNames.contains(serviceName)
}

object CloudRun {

  final class ForService(run: CloudRun, serviceYamlFilePath: String) {
    private val service: Service = parseServiceYaml(serviceYamlFilePath)
    def serviceName: String = getServiceName(service)
    def containerImage: String = getContainerImage(service)
    def getServiceYaml: String = run.getServiceYaml(serviceName)
    def deploy: Service = run.deployService(service)
    def getLatestRevisionYaml: String = run.getLatestRevisionYaml(serviceName)
  }

  val applicationName: String = "podval-google-cloud-run"

  val cloudServiceAccountKeyPropertyDefault: String = "gcloudServiceAccountKey"

  def utf8: Charset = Charset.forName("UTF-8")

  def jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance

  // authentication - see
  //   https://github.com/googleapis/google-auth-library-java#google-auth-library-oauth2-http
  // (what is ServiceAccountJwtAccessCredentials.fromStream(keyStream) for?)
  def key2credentials(key: String): ServiceAccountCredentials = ServiceAccountCredentials
    .fromStream(string2stream(key))
    .createScoped(CloudRunScopes.all)
    .asInstanceOf[ServiceAccountCredentials]

  def key2property(key: String): String = {
    s"$cloudServiceAccountKeyPropertyDefault= \\\n" ++
      key
        .replace("\n", " \\\n")
        .replace("\\n", "\\\\n")
  }

  // Note: objectMapper.readValue(inputStream, classOf[Service]) throws
  //   java.lang.IllegalArgumentException:
  //   Can not set com.google.api.services.run.v1.model.ObjectMeta field
  //   com.google.api.services.run.v1.model.Service.metadata to java.util.LinkedHashMap
  // so I convert YAML into a JSON string and then parse it using Google's parser:
  def parseServiceYaml(path: String): Service = new JsonObjectParser(jsonFactory).parseAndClose(
    string2stream(yaml2json(path)),
    utf8,
    classOf[Service]
  )

  def getServiceName(service: Service): String = service.getMetadata.getName

  // image name of the first container!
  def getContainerImage(service: Service): String = service.getSpec.getTemplate.getSpec.getContainers.get(0).getImage

  def yaml2json(yamlFilePath: String): String = {
    val yamlInputStream: InputStream = new FileInputStream(new File(yamlFilePath))
    val objectMapper: ObjectMapper = new ObjectMapper(new YAMLFactory)
    objectMapper.readTree(yamlInputStream).toString
  }

  def json2yaml(value: AnyRef): String = {
    val yamlFactory: YAMLFactory = new YAMLFactory

    // suppress leading "---"
    yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)

    new ObjectMapper(yamlFactory).writeValueAsString(value)
  }

  def string2stream(string: String): InputStream =
    new ByteArrayInputStream(string.getBytes(utf8))

  def file2string(path: String): String = {
    val source: Source = Source.fromFile(path)
    val result: String = source.getLines.mkString("\n")
    source.close()
    result
  }

//  def main(args: Array[String]): Unit = {
//    val service: ForService = new ForService(
//      new CloudRun(
//        file2string("/home/dub/.gradle/gcloudServiceAccountKey.json"),
//        region = "us-east4"
//      ),
//      "service.yaml"
//    )
//
//    println(service.getLatestRevisionYaml)
//  }
}
