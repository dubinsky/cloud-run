package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Configuration, Container, ObjectMeta, Revision, Route, Service}
import org.slf4j.Logger
import scala.jdk.CollectionConverters.{ListHasAsScala, MapHasAsScala}
import java.math.{MathContext, RoundingMode}

final class CloudRunService(
  run: CloudRunClient,
  service: Service,
  log: Logger
):
  private def serviceName: String = CloudRunService.name(service)

  def get: Service = run.getService(serviceName)

  def getConfiguration: Configuration = run.getConfiguration(serviceName)

  def getRoute: Route = run.getRoute(serviceName)

  def getRevision(revisionName: String): Revision = run.getRevision(revisionName)

  def getLatestRevision: Revision = getRevision(get.getStatus.getLatestCreatedRevisionName)

  def deploy(): Service =
    val current: Option[Service] = scala.util.Try(get).toOption

    val nextGeneration: Int = 1 + current
      .flatMap(current => Option(current.getMetadata.getGeneration))
      .map(_.toInt)
      .getOrElse(0)

    val revisionName: String = f"$serviceName-$nextGeneration%05d-${ThreeLetterWord.get(validate = true)}"

    // add some annotations, just as `gcloud deploy` does
    // (although I do not understand why they are added in both places and why is user-image one needed)
    // see https://github.com/twistedpair/google-cloud-sdk/blob/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/run/serverless_operations.py
    val annotations: Map[String, String] = Map(
      "run.googleapis.com/client-name"    -> CloudRunClient.applicationName,
      "run.googleapis.com/client-version" -> CloudRunClient.applicationVersion,
      "client.knative.dev/user-image"     -> CloudRunService.containerImage(service)
    )

    val next: Service = service.clone

    // make sure spec.template.metadata is there - we are going to modify it
    if next.getSpec.getTemplate.getMetadata == null then
      next.getSpec.getTemplate.setMetadata(new ObjectMeta)

    // set revision name to force new revision even if nothing changed in the configuration
    next.getSpec.getTemplate.getMetadata.setName(revisionName)

    CloudRunService.addAnnotationsTo(annotations, next.getMetadata)
    CloudRunService.addAnnotationsTo(annotations, next.getSpec.getTemplate.getMetadata)

    log.warn(s"Deploying service [$serviceName] revision [$revisionName] in project [${run.projectId}] region [${run.region}].")

    if current.isEmpty then run.createService(next) else run.replaceService(next)

    StatusTracker(log, stages = Seq(
      StatusTracker.Stage("Service ", () => get                      .getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getConfiguration         .getStatus.getConditions),
      StatusTracker.Stage("Revision", () => getRevision(revisionName).getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getRoute                 .getStatus.getConditions)
    )).track()

    log.warn(s"Service [$serviceName] revision [$revisionName] has been deployed and is serving 100% of traffic.")

    val result: Service = get
    log.warn(s"Service URL: ${result.getStatus.getUrl}")
    result

object CloudRunService:
  private def addAnnotationsTo(map: Map[String, String], result: ObjectMeta): Unit =
    if result.getAnnotations == null then
      result.setAnnotations(new java.util.HashMap[String, String]())

    val annotations: java.util.Map[String, String] = result.getAnnotations
    for ((key, value) <- map) annotations.put(key, value)

  def name(service: Service): String = service.getMetadata.getName

  def containerImage(service: Service): String = firstContainer(service).getImage

  private def resourceLimit(service: Service, name: String, default: String): String = Option(firstContainer(service).getResources)
    .flatMap(resourceRequirements => Option(resourceRequirements.getLimits))
    .flatMap(limits => Option(limits.get(name)))
    .getOrElse(default)

  private def firstContainer(service: Service): Container =
    service.getSpec.getTemplate.getSpec.getContainers.get(0)

  def localDockerCommandLine(service: Service, additionalOptions: Seq[String]): Seq[String] =
    val container: Container = firstContainer(service)
    val port: Int = Option(container.getPorts)
      .flatMap(_.asScala.headOption)
      .flatMap(containerPort => Option(containerPort.getContainerPort))
      .map(_.toInt)
      .getOrElse(8080)

    Seq(
      "docker", "run",
      "--rm",
      "--name", name(service),
      "--cpus", cpuToFloat(resourceLimit(service, "cpu", "1000m")).toString,
      "--memory", resourceLimit(service, "memory", "512Mi")
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
        containerImage(service)
      ) ++
      // TODO use --entrypoint for the first string (see
      // https://docs.docker.com/engine/reference/run/#entrypoint-default-command-to-execute-at-runtime)?
      getList(container.getCommand) ++
      getList(container.getArgs)

  private def cpuToFloat(cpuStr: String): Float =
    val result: Double = if cpuStr.endsWith("m") then cpuStr.init.toFloat / 1000.0 else cpuStr.toFloat
    BigDecimal(result, mathContext).floatValue

  private def mathContext: MathContext = MathContext(3, RoundingMode.HALF_UP)

  private def getMap[T](getter: => T)(mapper: T => Map[String, String]): Map[String, String] =
    Option(getter).map(mapper).getOrElse(Map.empty)

  private def getList(getter: => java.util.List[String]): Seq[String] =
    Option(getter).map(_.asScala.toSeq).getOrElse(Seq.empty)

  private def list(optionName: String, map: Map[String, String]): Seq[String] =
    map.toSeq.flatMap { (name, value) => Seq(optionName, if value.isEmpty then s"$name" else s"name=value") }