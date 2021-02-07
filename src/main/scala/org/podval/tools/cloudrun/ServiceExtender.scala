package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Container, ObjectMeta, Service}
import java.math.{MathContext, RoundingMode}
import scala.jdk.CollectionConverters.{ListHasAsScala, MapHasAsScala}

object ServiceExtender {

  implicit class Operations(service: Service) {
    def name: String = service.getMetadata.getName

    def generation: Option[Int] =
      Option(service.getMetadata.getGeneration)

    def labels: Map[String, String] =
      getMap(service.getMetadata.getLabels)(_.asScala.toMap)

    def env: Map[String, String] =
      getMap(firstContainer.getEnv)(_.asScala.map(envVar => envVar.getName -> envVar.getValue).toMap)

    private def getMap[T](getter: => T)(mapper: T => Map[String, String]): Map[String, String] =
      Option(getter).map(mapper).getOrElse(Map.empty)

    def addAnnotations(map: Map[String, String]): Service =
      update(result => addAnnotations(map, result.getMetadata))

    def addSpecAnnotations(map: Map[String, String]): Service =
      update(result => addAnnotations(map, result.getSpec.getTemplate.getMetadata))

    private def addAnnotations(map: Map[String, String], result: ObjectMeta): Unit = {
      val annotations: java.util.Map[String, String] = result.getAnnotations // TODO null?
      for ((key, value) <- map) annotations.put(key, value)
    }

    def setRevisionName(value: String): Service =
      update(_.getSpec.getTemplate.getMetadata.setName(value))

    private def update(f: Service => Unit): Service = {
      val result: Service = service.clone
      f(result)
      result
    }

    def containerImage: String = firstContainer.getImage

    def containerPort:Option[Int] =
      Option(firstContainer.getPorts).flatMap(ports => Option(ports.asScala.head.getContainerPort.toInt))

    def cpu: String = resourceLimit("cpu")

    def memory: String = resourceLimit("memory")

    private def resourceLimit(name: String): String =
      Option(firstContainer.getResources.getLimits.get(name))
      .getOrElse(throw new IllegalArgumentException(s"spec.template.spec.containers(0).$name is missing!"))

    def command: Option[Seq[String]] = getList(firstContainer.getCommand)

    def args: Option[Seq[String]] = getList(firstContainer.getArgs)

    private def getList(getter: => java.util.List[String]): Option[Seq[String]] =
      Option(getter).map(_.asScala.toSeq)

    private def firstContainer: Container =
      service.getSpec.getTemplate.getSpec.getContainers.get(0)
  }

  private val mathContext: MathContext = new MathContext(3, RoundingMode.HALF_UP)

  private def cpuToFloat(cpuStr: String): Float = {
    val result: Double = if (cpuStr.endsWith("m")) cpuStr.init.toFloat / 1000.0 else cpuStr.toFloat
    BigDecimal(result, mathContext).floatValue
  }

  def dockerCommandLine(service: Service, additionalOptions: Seq[String]): Seq[String] = {
    val port: Int = service.containerPort.getOrElse(8080)

    Seq(
      "docker", "run", "--rm",
      "--name"   , service.name,
      "--cpus"   , cpuToFloat(service.cpu).toString,
      "--memory" , service.memory
    ) ++
    list("--label", service.labels) ++
    list("--env"  , service.env   ) ++
    Seq(
      "--env"    , s"PORT=$port",
      "--publish", s"$port:$port"
    ) ++
    additionalOptions ++
    Seq(
      service.containerImage
    ) ++
    // TODO use --entrypoint for the first string (see https://docs.docker.com/engine/reference/run/#entrypoint-default-command-to-execute-at-runtime)?
    service.command.toSeq.flatten ++
    service.args.toSeq.flatten
  }

  private def list(optionName: String, map: Map[String, String]): Seq[String] =
    map.toSeq.flatMap { case (name, value) => Seq(optionName, if (value.isEmpty) s"$name" else s"name=value") }
}
