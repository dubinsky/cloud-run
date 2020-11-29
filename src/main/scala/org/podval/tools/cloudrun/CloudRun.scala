package org.podval.tools.cloudrun

import java.io.{ByteArrayInputStream, File, FileInputStream, InputStream}
import java.nio.charset.Charset
import com.fasterxml.jackson.core.JsonParseException
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
  credentials: ServiceAccountCredentials,
  region: String
) {
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

  def listServiceNames: List[String] = listServices.map(CloudRun.getServiceName)

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
    // TODO why does the GitHub Action merge previous into service?
    //   (see https://github.com/google-github-actions/deploy-cloudrun/blob/e6563531efecd65332243ad924e3dcf72681c41a/src/service.ts#L138)
    // from the replaceService() JavaDoc:
    //  Only the spec and metadata labels and annotations are modifiable.
    //  After the Update request, Cloud Run will work to make the 'status' match the requested 'spec'.
    /*
    public merge(prevService: run_v1.Schema$Service): void {
    // Merge Revision Metadata
    const labels = {
      ...prevService.spec?.template?.metadata?.labels,
      ...this.request.spec?.template?.metadata?.labels,
    };
    const annotations = {
      ...prevService.spec?.template?.metadata?.annotations,
      ...this.request.spec?.template?.metadata?.annotations,
    };
    this.request.spec!.template!.metadata = {
      annotations,
      labels,
    };

    // Merge Revision Spec
    const prevContainer = prevService.spec!.template!.spec!.containers![0];
    const currentContainer = this.request.spec!.template!.spec!.containers![0];
    // Merge Container spec
    const container = { ...prevContainer, ...currentContainer };
    // Merge Spec
    const spec = {
      ...prevService.spec?.template?.spec,
      ...this.request.spec!.template!.spec,
    };
    if (!currentContainer.command) {
      // Remove entrypoint cmd and arguments if not specified
      delete container.command;
      delete container.args;
    }
    // Merge Env vars
    let env: run_v1.Schema$EnvVar[] = [];
    if (currentContainer.env) {
      env = currentContainer.env.map(
        (envVar) => envVar as run_v1.Schema$EnvVar,
      );
    }
    const keys = env?.map((envVar) => envVar.name);
    prevContainer.env?.forEach((envVar) => {
      if (!keys.includes(envVar.name)) {
        return env.push(envVar);
      }
    });
    container.env = env;
    spec.containers = [container];
    this.request.spec!.template!.spec = spec;
  }
}
     */
    replaceService(service)
  }

  def replaceService(service: Service): Service = {
    val serviceName: String = CloudRun.getServiceName(service)
    client
      .namespaces().services().replaceService(s"namespaces/$projectId/services/$serviceName", service)
      .execute()
  }

  def listRevisions: List[Revision] = client
    .namespaces().revisions().list(s"namespaces/$projectId")
    .execute().getItems.asScala.toList

  def getRevision(revisionName: String): Revision = client
    .namespaces().revisions().get(s"namespaces/$projectId/revisions/$revisionName")
    .execute()

  def getLatestRevision(serviceName: String): Revision =
    getRevision(getService(serviceName).getStatus.getLatestCreatedRevisionName)

  def getLatestRevisionYaml(serviceName: String): String = CloudRun.json2yaml(getLatestRevision(serviceName))

  def exists(serviceName: String): Boolean =
    listServiceNames.contains(serviceName)

  def forService(service: Service): CloudRun.ForService =
    new CloudRun.ForService(this, service)

  def forServiceYaml(serviceYamlFilePath: String): CloudRun.ForService =
    forService(CloudRun.parseServiceYaml(serviceYamlFilePath))
}

object CloudRun {

  final class ForService(val run: CloudRun, val service: Service) {
    def serviceName: String = CloudRun.getServiceName(service)

    def containerImage: String = CloudRun.getContainerImage(service)

    def getServiceYaml: String = run.getServiceYaml(serviceName)

    def deploy: Service = run.deployService(service)

    def getLatestRevisionYaml: String = run.getLatestRevisionYaml(serviceName)
  }

  val applicationName: String = "podval-google-cloud-run"

  def utf8: Charset = Charset.forName("UTF-8")

  def jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance

  def keyOrFile2credentials(key: String): ServiceAccountCredentials = {
    try { key2credentials(key) } catch {
      case e: JsonParseException =>
        val keyFromFile: String = file2string(key)
        println(s"Error parsing key: ${e.getMessage}")
        println(s"Trying file at $key")
        val result = key2credentials(keyFromFile)
        println("Add the following property to your .gradle/gradle.properties file:")
        println(key2property(keyFromFile))
        result
    }
  }

  // authentication - see
  //   https://github.com/googleapis/google-auth-library-java#google-auth-library-oauth2-http
  // (what is ServiceAccountJwtAccessCredentials.fromStream(keyStream) for?)
  def key2credentials(key: String): ServiceAccountCredentials = ServiceAccountCredentials
    .fromStream(CloudRun.string2stream(key))
    .createScoped(CloudRunScopes.all)
    .asInstanceOf[ServiceAccountCredentials]

  def key2property(key: String): String = {
    "gcloudServiceAccountKey= \\\n" ++
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

  def main(args: Array[String]): Unit = {
    val service: ForService = new CloudRun(
      CloudRun.key2credentials(CloudRun.file2string("/home/dub/.gradle/gcloudServiceAccountKey-alter-rebbe-2.json")),
      region = "us-east4"
    ).forServiceYaml("service.yaml")

    // println(service.getServiceYaml)
    //    println(service.getLatestRevisionYaml)
    println(json2yaml(service.deploy))
  }
}
