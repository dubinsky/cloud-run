package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Container, ObjectMeta, Service}
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

    def addAnnotations(map: Map[String, String]): Service =
      update(result => addAnnotationsTo(map, result.getMetadata))

    def addSpecAnnotations(map: Map[String, String]): Service =
      update(result => addAnnotationsTo(map, result.getSpec.getTemplate.getMetadata))

    def setRevisionName(value: String): Service =
      update(_.getSpec.getTemplate.getMetadata.setName(value))

    private def update(f: Service => Unit): Service =
      val result: Service = service.clone
      f(result)
      result

    def containerImage: String = firstContainer.getImage

    def containerPort:Option[Int] =
      Option(firstContainer.getPorts).flatMap(ports => Option(ports.asScala.head.getContainerPort.toInt))

    def cpu: String = resourceLimit("cpu")

    def cpuFloat: String = Util.cpuToFloat(cpu).toString

    def memory: String = resourceLimit("memory")

    private def resourceLimit(name: String): String =
      Option(firstContainer.getResources.getLimits.get(name))
      .getOrElse(throw IllegalArgumentException(s"spec.template.spec.containers(0).$name is missing!"))

    def command: Option[Seq[String]] = getList(firstContainer.getCommand)

    def args: Option[Seq[String]] = getList(firstContainer.getArgs)

    private def firstContainer: Container =
      service.getSpec.getTemplate.getSpec.getContainers.get(0)

  private def getMap[T](getter: => T)(mapper: T => Map[String, String]): Map[String, String] =
    Option(getter).map(mapper).getOrElse(Map.empty)

  private def getList(getter: => java.util.List[String]): Option[Seq[String]] =
    Option(getter).map(_.asScala.toSeq)

  private def addAnnotationsTo(map: Map[String, String], result: ObjectMeta): Unit =
    val annotations: java.util.Map[String, String] = result.getAnnotations // TODO null?
    for ((key, value) <- map) annotations.put(key, value)
