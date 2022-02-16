package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Configuration, Revision, Route, Service}
import ServiceExtender.*
import org.slf4j.Logger

final class CloudRunService(
  serviceAccountKey: Option[String],
  region: String,
  service: Service,
  log: Logger
):
  private val run: CloudRun = CloudRun(
    serviceAccountKey = serviceAccountKey.getOrElse(throw IllegalArgumentException("No service account key!")),
    region = region
  )

  def get: Service = run.getService(service.name)

  def getYaml: String = "Latest Service YAML:\n" + Util.json2yaml(get)

  def getConfiguration: Configuration = run.getConfiguration(service.name)

  def getRoute: Route = run.getRoute(service.name)

  def getRevision(revisionName: String): Revision = run.getRevision(revisionName)

  def getLatestRevision: Revision = getRevision(get.getStatus.getLatestCreatedRevisionName)

  def getLatestRevisionYaml: String = "Latest Revision YAML:\n" + Util.json2yaml(getLatestRevision)

  def deploy(): Service =
    val current: Option[Service] = scala.util.Try(get).toOption
    val nextGeneration: Int = current.flatMap(_.generation).getOrElse(0) + 1
    val revisionName: String = f"${service.name}-$nextGeneration%05d-${ThreeLetterWord.get(validate = true)}"

    val annotations: Map[String, String] = Map(
      "run.googleapis.com/client-name"    -> CloudRun.applicationName,
      "run.googleapis.com/client-version" -> CloudRun.applicationVersion,
      "client.knative.dev/user-image"     -> service.containerImage // TODO why?
    )

    val next: Service = service
      // add some annotations, just as `gcloud deploy` does
      // see https://github.com/twistedpair/google-cloud-sdk/blob/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/run/serverless_operations.py
      .addAnnotations(annotations)
      .addSpecAnnotations(annotations) // TODO why?
      // set revision name to force new revision even if nothing changed in the configuration
      .setRevisionName(revisionName)

    log.warn(s"Deploying service [${service.name}] revision [$revisionName] in project [${run.projectId}] region [${run.region}].")

    if current.isEmpty then run.createService(next) else run.replaceService(next)

    StatusTracker(log, stages = Seq(
      StatusTracker.Stage("Service ", () => get                      .getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getConfiguration         .getStatus.getConditions),
      StatusTracker.Stage("Revision", () => getRevision(revisionName).getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getRoute                 .getStatus.getConditions)
    )).track()

    log.warn(s"Service [${service.name}] revision [$revisionName] has been deployed and is serving 100% of traffic.")

    val result: Service = get
    log.warn(s"Service URL: ${result.getStatus.getUrl}")
    result

object CloudRunService:

  def main(args: Array[String]): Unit =
    val cloudRunService = CloudRunService(
      serviceAccountKey = Util.getServiceAccountKeyFromGradleProperties,
      region = "us-east4",
      service = Util.readServiceYaml("../../OpenTorah/opentorah.org/collector/service.yaml"),
      log = org.slf4j.LoggerFactory.getLogger(classOf[CloudRun])
    )
    val service: Service = cloudRunService.get
    val revision: Revision = cloudRunService.getLatestRevision

    println(cloudRunService.getYaml)
    println(s"${service.name}: ${revision.getMetadata.getName} at ${service.getStatus.getUrl}")
