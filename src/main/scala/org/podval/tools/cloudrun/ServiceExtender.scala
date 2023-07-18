package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Container, Service}
import scala.jdk.CollectionConverters.{ListHasAsScala, MapHasAsScala}

object ServiceExtender:

  extension(service: Service)
    def name: String = service.getMetadata.getName

    def generation: Option[Int] =
      Option(service.getMetadata.getGeneration)

    def labels: Map[String, String] =
      getMap(service.getMetadata.getLabels)(_.asScala.toMap)

    def env: Map[String, String] =
      getMap(firstContainer.getEnv)(_.asScala.map(envVar => envVar.getName -> envVar.getValue).toMap)

    def containerImage: String = firstContainer.getImage

    def containerPort: Option[Int] = Option(firstContainer.getPorts)
      .flatMap(_.asScala.headOption)
      .flatMap(containerPort => Option(containerPort.getContainerPort))
      .map(_.toInt)

    def cpu: String = resourceLimit("cpu", "1000m")

    def cpuFloat: String = Util.cpuToFloat(cpu).toString

    def memory: String = resourceLimit("memory", "512Mi")
    
    private def resourceLimit(name: String, default: String): String = Option(firstContainer.getResources)
      .flatMap(container => Option(container.getLimits))
      .flatMap(limits => Option(limits.get(name)))
      .getOrElse(default)

    def command: Option[Seq[String]] = getList(firstContainer.getCommand)

    def args: Option[Seq[String]] = getList(firstContainer.getArgs)

    private def firstContainer: Container =
      service.getSpec.getTemplate.getSpec.getContainers.get(0)

  private def getMap[T](getter: => T)(mapper: T => Map[String, String]): Map[String, String] =
    Option(getter).map(mapper).getOrElse(Map.empty)

  private def getList(getter: => java.util.List[String]): Option[Seq[String]] =
    Option(getter).map(_.asScala.toSeq)

