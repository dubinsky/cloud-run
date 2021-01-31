package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Configuration, ObjectMeta, Revision, Route, Service}

final class CloudRunService(run: CloudRun, service: Service) {

  private def log: String => Unit = run.log

  private def serviceName: String = CloudRun.getServiceName(service)

  def deploy(): Service = {
    val previous: Option[Service] = scala.util.Try(get).toOption
    val previousGeneration: Integer = previous.map(_.getMetadata.getGeneration).getOrElse(0)
    val revisionName: String = f"$serviceName-${previousGeneration + 1}%05d-${ThreeLetterWord.get}"

    val next: Service = service.clone()

    // add some annotations, just as `gcloud deploy` does
    // see https://github.com/twistedpair/google-cloud-sdk/blob/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/run/serverless_operations.py
    addAnnotations(next.getMetadata)
    addAnnotations(next.getSpec.getTemplate.getMetadata) // TODO why?

    // set revision name to force new revision even if nothing changed in the configuration
    next.getSpec.getTemplate.getMetadata.setName(revisionName)

    log(s"Deploying service [$serviceName] revision [$revisionName] in project [${run.projectId}] region [${run.region}].")

    previous.fold(run.createService(next))(_ => run.replaceService(serviceName, next))

    new StatusTracker(log, stages = Seq(
      StatusTracker.Stage("Service ", () => get             .getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getConfiguration.getStatus.getConditions),
      StatusTracker.Stage("Revision", () => run.getRevision(revisionName).getStatus.getConditions),
      StatusTracker.Stage("Route   ", () => getRoute        .getStatus.getConditions)
    )).track()

    log(s"Service [$serviceName] revision [$revisionName] has been deployed and is serving 100% of traffic.")

    val result: Service = get
    log(s"Service URL: ${result.getStatus.getUrl}")
    result
  }

  private def addAnnotations(metadata: ObjectMeta): Unit = {
    val annotations: java.util.Map[String, String] = metadata.getAnnotations
    annotations.put("run.googleapis.com/client-name"   , CloudRun.applicationName)
    annotations.put("run.googleapis.com/client-version", CloudRun.applicationVersion)
    annotations.put("client.knative.dev/user-image"    , CloudRun.getContainerImage(service)) // TODO why?
  }

  def get: Service = run.getService(serviceName)

  def describe(): Unit = log("Latest Service YAML:\n" + CloudRun.json2yaml(get))

  def getConfiguration: Configuration = run.getConfiguration(serviceName)

  def getRoute: Route = run.getRoute(serviceName)

  def getLatestRevision: Revision = run.getRevision(get.getStatus.getLatestCreatedRevisionName)

  def describeLatestRevision(): Unit = log("Latest Revision YAML:\n" + CloudRun.json2yaml(getLatestRevision))
}
