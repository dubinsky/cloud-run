package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Configuration, ObjectMeta, Revision, Route, Service}
import com.google.auth.oauth2.ServiceAccountCredentials
import org.slf4j.Logger

final class CloudRun(
  client: CloudRunClient,
  service: CloudRunService,
  logger: Logger
):
  def get: Service = client.getService(service.name)

  def getConfiguration: Configuration = client.getConfiguration(service.name)

  def getRoute: Route = client.getRoute(service.name)

  def getRevision(revisionName: String): Revision = client.getRevision(revisionName)

  def getLatestRevision: Revision = getRevision(get.getStatus.getLatestCreatedRevisionName)

  def deploy(): Service =
    val current: Option[Service] = scala.util.Try(get).toOption

    val nextGeneration: Int = 1 + current
      .flatMap(current => Option(current.getMetadata.getGeneration))
      .map(_.toInt)
      .getOrElse(0)

    val revisionName: String = f"${service.name}-$nextGeneration%05d-${ThreeLetterWord.get(validate = true)}"

    // add some annotations, just as `gcloud deploy` does
    // (although I do not understand why they are added in both places and why is user-image one needed)
    // see https://github.com/twistedpair/google-cloud-sdk/blob/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/run/serverless_operations.py
    val annotations: Map[String, String] = Map(
      "run.googleapis.com/client-name"    -> CloudRunClient.applicationName,
      "run.googleapis.com/client-version" -> CloudRunClient.applicationVersion,
      "client.knative.dev/user-image"     -> service.containerImage
    )

    val next: Service = service.service.clone

    // make sure spec.template.metadata is there - we are going to modify it
    if next.getSpec.getTemplate.getMetadata == null then
      next.getSpec.getTemplate.setMetadata(new ObjectMeta)

    // set revision name to force new revision even if nothing changed in the configuration
    next.getSpec.getTemplate.getMetadata.setName(revisionName)

    CloudRun.addAnnotationsTo(annotations, next.getMetadata)
    CloudRun.addAnnotationsTo(annotations, next.getSpec.getTemplate.getMetadata)

    logger.warn(s"Deploying service [${service.name}] revision [$revisionName] in project [${client.projectId}] region [${client.region}].")

    if current.isEmpty then client.createService(next) else client.replaceService(next)

    StatusTracker(logger, stages = Seq(
      StatusTracker.Stage("Service ", () => get                      .getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getConfiguration         .getStatus.getConditions),
      StatusTracker.Stage("Revision", () => getRevision(revisionName).getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getRoute                 .getStatus.getConditions)
    )).track()

    logger.warn(s"Service [${service.name}] revision [$revisionName] has been deployed and is serving 100% of traffic.")

    val result: Service = get
    logger.warn(s"Service URL: ${result.getStatus.getUrl}")
    result

object CloudRun:
  def apply(
    service: CloudRunService,
    serviceAccountKey: Option[String],
    region: String,
    logger: Logger
  ): CloudRun =
    val credentials: ServiceAccountCredentials = Key.credentials(serviceAccountKey)
    val containerImage: String = service.containerImage
    val containerImageSegments: Array[String] = containerImage.split('/')
    require(containerImageSegments.length == 3)

    def verify(index: Int, expected: String, what: String): Unit =
      val value: String = containerImageSegments(index)
      require(value == expected, s"Unexpected $what in the image name $containerImage: $value instead of $expected")

    verify(0, "gcr.io", "image repository")
    verify(1, credentials.getProjectId, "project id")
    verify(2, service.name, "service name")

    new CloudRun(
      client = CloudRunClient(credentials, region),
      service = service,
      logger = logger
    )

  private def addAnnotationsTo(map: Map[String, String], result: ObjectMeta): Unit =
    if result.getAnnotations == null then
      result.setAnnotations(new java.util.HashMap[String, String]())

    val annotations: java.util.Map[String, String] = result.getAnnotations
    for ((key, value) <- map) annotations.put(key, value)
