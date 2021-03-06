package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Configuration, Revision, Route, Service}
import org.podval.tools.cloudrun.ServiceExtender.Operations
import org.slf4j.Logger

final class CloudRunService(run: CloudRun, service: Service) {

  private def log: Logger = run.log

  def get: Service = run.getService(service.name)

  def getConfiguration: Configuration = run.getConfiguration(service.name)

  def getRoute: Route = run.getRoute(service.name)

  def getRevision(revisionName: String): Revision = run.getRevision(revisionName)

  def getLatestRevision: Revision = getRevision(get.getStatus.getLatestCreatedRevisionName)

  def deploy(): Service = {
    val current: Option[Service] = scala.util.Try(get).toOption
    val nextGeneration: Integer = current.flatMap(_.generation).getOrElse(0) + 1
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

    if (current.isEmpty) run.createService(next) else run.replaceService(next)

    new StatusTracker(log, stages = Seq(
      StatusTracker.Stage("Service ", () => get                      .getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getConfiguration         .getStatus.getConditions),
      StatusTracker.Stage("Revision", () => getRevision(revisionName).getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getRoute                 .getStatus.getConditions)
    )).track()

    log.warn(s"Service [${service.name}] revision [$revisionName] has been deployed and is serving 100% of traffic.")

    val result: Service = get
    log.warn(s"Service URL: ${result.getStatus.getUrl}")
    result
  }
}
