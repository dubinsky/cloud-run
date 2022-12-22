package org.podval.tools.cloudrun

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.{YAMLFactory, YAMLGenerator}
import com.google.api.client.json.{JsonFactory, JsonObjectParser}
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.run.v1.model.Service
import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.api.{Project, Task}
import java.io.{ByteArrayInputStream, InputStream}
import java.math.{MathContext, RoundingMode}
import java.nio.charset.Charset
import scala.io.Source

object Util:
  def jsonFactory: JsonFactory = GsonFactory.getDefaultInstance

  def readServiceYaml(serviceYamlFilePath: String): Service = Util.yamlFile2[Service](
    clazz = classOf[Service],
    yamlFilePath = serviceYamlFilePath
  )

  // Note: when parsing Service YAML, objectMapper.readValue(inputStream, classOf[Service]) throws
  //   java.lang.IllegalArgumentException:
  //   Can not set com.google.api.services.run.v1.model.ObjectMeta field
  //   com.google.api.services.run.v1.model.Service.metadata to java.util.LinkedHashMap
  // so I convert YAML into a JSON string and then parse it using Google's parser:
  private def yamlFile2[T](clazz: Class[T], yamlFilePath: String): T =
    val json: String = yamlObjectMapper
      .readTree(file2string(yamlFilePath))
      .toString

    JsonObjectParser(jsonFactory).parseAndClose(
      string2stream(json),
      utf8,
      clazz
    )

  def json2yaml(value: AnyRef): String = yamlObjectMapper.writeValueAsString(value)

  private def yamlObjectMapper: ObjectMapper =
    val yamlFactory: YAMLFactory = YAMLFactory()
    yamlFactory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER) // suppress leading "---"
    ObjectMapper(yamlFactory)

  def file2string(path: String): String = source2string(Source.fromFile(path))

  // def stream2string(stream: InputStream): String = source2string(Source.fromInputStream(stream)(Codec(utf8)))

  private def source2string(source: Source): String =
    val result: String = source.getLines().mkString("\n")
    source.close()
    result

  def string2stream(string: String): InputStream = ByteArrayInputStream(string.getBytes(utf8))

  private def utf8: Charset = Charset.forName("UTF-8")

  // Extension with the name 'jib' comes from the [JIB plugin](https://github.com/GoogleContainerTools/jib);
  // if it exists, tasks 'jib' and 'jibDockerBuild' exist too.
  def getJib(project: Project): Option[JibExtension] = Option(project.getExtensions.findByType(classOf[JibExtension]))

  def list(optionName: String, map: Map[String, String]): Seq[String] =
    map.toSeq.flatMap { (name, value) => Seq(optionName, if value.isEmpty then s"$name" else s"name=value") }

  private def mathContext: MathContext = MathContext(3, RoundingMode.HALF_UP)

  def cpuToFloat(cpuStr: String): Float =
    val result: Double = if cpuStr.endsWith("m") then cpuStr.init.toFloat / 1000.0 else cpuStr.toFloat
    BigDecimal(result, mathContext).floatValue

  def getServiceAccountKeyFromGradleProperties: Option[String] =
    val properties: java.util.Properties = java.util.Properties()
    val home: String = System.getenv("HOME")
    properties.load(java.io.FileInputStream(java.io.File(s"$home/.gradle/gradle.properties")))
    Option(properties.getProperty(CloudRunExtension.serviceAccountKeyPropertyDefault))
