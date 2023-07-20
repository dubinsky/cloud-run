package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Container, Service}
import java.math.{MathContext, RoundingMode}
import scala.jdk.CollectionConverters.{ListHasAsScala, MapHasAsScala}

final class CloudRunService(val service: Service):
  // verify that minimal configuration is present
  private def verifyNotNull(getter: Service => AnyRef, what: String): Unit =
    require(getter(service) != null, s"$what is missing!")

  verifyNotNull(_.getMetadata, "metadata")
  verifyNotNull(_.getMetadata.getName, "metadata.name")
  verifyNotNull(_.getSpec, "spec")
  verifyNotNull(_.getSpec.getTemplate, "spec.template")
  verifyNotNull(_.getSpec.getTemplate.getSpec, "spec.template.spec")
  verifyNotNull(_.getSpec.getTemplate.getSpec.getContainers, "spec.template.spec.containers")
  verifyNotNull(_.getSpec.getTemplate.getSpec.getContainers.get(0), "spec.template.spec.containers(0)")
  verifyNotNull(_.getSpec.getTemplate.getSpec.getContainers.get(0).getImage, "spec.template.spec.containers(0).image")
  
  def name: String = CloudRunService.name(service)

  private def firstContainer: Container =
    service.getSpec.getTemplate.getSpec.getContainers.get(0)

  def containerImage: String = firstContainer.getImage

  private def resourceLimit(name: String, default: String): String = Option(firstContainer.getResources)
    .flatMap(resourceRequirements => Option(resourceRequirements.getLimits))
    .flatMap(limits => Option(limits.get(name)))
    .getOrElse(default)

  private def cpuToFloat(cpuStr: String): Float =
    val result: Double = if cpuStr.endsWith("m") then cpuStr.init.toFloat / 1000.0 else cpuStr.toFloat
    BigDecimal(result, mathContext).floatValue

  private def mathContext: MathContext = MathContext(3, RoundingMode.HALF_UP)

  private def getMap[T](getter: => T)(mapper: T => Map[String, String]): Map[String, String] =
    Option(getter).map(mapper).getOrElse(Map.empty)

  private def getList(getter: => java.util.List[String]): Seq[String] =
    Option(getter).map(_.asScala.toSeq).getOrElse(Seq.empty)

  private def list(optionName: String, map: Map[String, String]): Seq[String] =
    map.toSeq.flatMap((name, value) => Seq(optionName, if value.isEmpty then s"$name" else s"name=value"))

  def localDockerCommandLine(additionalOptions: Seq[String]): Seq[String] =
    val container: Container = firstContainer
    val port: Int = Option(container.getPorts)
      .flatMap(_.asScala.headOption)
      .flatMap(containerPort => Option(containerPort.getContainerPort))
      .map(_.toInt)
      .getOrElse(8080)

    Seq(
      "docker", "run",
      "--rm",
      "--name", name,
      "--cpus", cpuToFloat(resourceLimit("cpu", "1000m")).toString,
      "--memory", resourceLimit("memory", "512Mi")
    ) ++
      list("--label",
        getMap(service.getMetadata.getLabels)(_.asScala.toMap)
      ) ++
      list("--env",
        getMap(container.getEnv)(_.asScala.map(envVar => envVar.getName -> envVar.getValue).toMap)
      ) ++
      Seq(
        "--env", s"PORT=$port",
        "--publish", s"$port:$port"
      ) ++
      additionalOptions ++
      Seq(
        containerImage
      ) ++
      // TODO use --entrypoint for the first string (see
      // https://docs.docker.com/engine/reference/run/#entrypoint-default-command-to-execute-at-runtime)?
      getList(container.getCommand) ++
      getList(container.getArgs)

object CloudRunService:
  def name(service: Service): String = service.getMetadata.getName
