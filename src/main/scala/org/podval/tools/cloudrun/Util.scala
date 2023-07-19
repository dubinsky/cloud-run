package org.podval.tools.cloudrun

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.{YAMLFactory, YAMLGenerator}
import com.google.api.client.json.{JsonFactory, JsonObjectParser}
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.run.v1.model.Service
import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.Charset
import scala.io.Source

object Util:
  def jsonFactory: JsonFactory = GsonFactory.getDefaultInstance

  // Note: when parsing Service YAML, objectMapper.readValue(inputStream, classOf[Service]) throws
  //   java.lang.IllegalArgumentException:
  //   Can not set com.google.api.services.run.v1.model.ObjectMeta field
  //   com.google.api.services.run.v1.model.Service.metadata to java.util.LinkedHashMap
  // so I convert YAML into a JSON string and then parse it using Google's parser:
  def readServiceYaml(serviceYamlFilePath: String): Service =
    val yaml: String = file2string(serviceYamlFilePath)
    val json: String = yamlObjectMapper
      .readTree(yaml)
      .toString

    JsonObjectParser(jsonFactory).parseAndClose(
      string2stream(json),
      utf8,
      classOf[Service]
    )

  def yamlObjectMapper: ObjectMapper =
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
  
  //  def getServiceAccountKeyFromGradleProperties: Option[String] =
//    val properties: java.util.Properties = java.util.Properties()
//    val home: String = System.getenv("HOME")
//    properties.load(java.io.FileInputStream(java.io.File(s"$home/.gradle/gradle.properties")))
//    Option(properties.getProperty(CloudRunExtension.serviceAccountKeyPropertyDefault))

  private def utf8: Charset = Charset.forName("UTF-8")
