package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{ObjectMeta, Revision, Route, Service, Status}
import org.slf4j.{Logger, LoggerFactory}
import scala.util.Try

final class CloudRunService(run: CloudRun, val service: Service) {

  private def logger: Logger = run.logger

  private def serviceName: String = service.getMetadata.getName

  // container image name of the first container!
  def containerImage: String = service.getSpec.getTemplate.getSpec.getContainers.get(0).getImage

  def deploy(): Unit =
    Try(get).toOption.fold {
      logger.warn(s"Creating new service")
      logger.info(CloudRun.json2yaml(service))
      logger.info(CloudRun.json2yaml(create))
    } { previous =>
      logger.warn(s"Redeploying existing service")
      logger.info(CloudRun.json2yaml(service))
      logger.info(CloudRun.json2yaml(redeploy(previous)))
    }

  def get: Service = run.getService(serviceName)

  def create: Service = run.createService(service)

  def replace: Service = run.replaceService(serviceName, service)

  def redeploy(previous: Service): Service = {
    val next: Service = service.clone()

    // add some annotations, just as `gcloud deploy` does
    // see https://github.com/twistedpair/google-cloud-sdk/blob/master/google-cloud-sdk/lib/googlecloudsdk/command_lib/run/serverless_operations.py
    addAnnotations(next.getMetadata)
    addAnnotations(next.getSpec.getTemplate.getMetadata) // TODO why?

    // set revision name to force new revision even if nothing changed in the configuration
    val revisionName: String = f"$serviceName-${previous.getMetadata.getGeneration + 1}%05d-${ThreeLetterWord.get}"
    next.getSpec.getTemplate.getMetadata.setName(revisionName)

    val result: Service = run.replaceService(serviceName, next)


    /*
        ('ConfigurationsReady', progress_tracker.Stage('Creating Revision...')),
        ('RoutesReady', progress_tracker.Stage('Routing traffic...')),
        ('Ready', progress_tracker.Stage('Readying...'))
     */
    StatusTracker.track(logger, stages = Seq(
      StatusTracker.Stage("Revision", () => run.getRevision(revisionName).getStatus.getConditions),
//      StatusTracker.Stage("Route"   , () => getRoute.getStatus.getConditions),
      StatusTracker.Stage("Service" , () => get     .getStatus.getConditions)
    ))

    result
  }

  private def addAnnotations(metadata: ObjectMeta): Unit = {
    val annotations: java.util.Map[String, String] = metadata.getAnnotations
    annotations.put("run.googleapis.com/client-name", CloudRun.applicationName)
    annotations.put("run.googleapis.com/client-version", CloudRun.applicationVersion)
    annotations.put("client.knative.dev/user-image", containerImage) // TODO why?
  }

  def delete: Status = run.deleteService(serviceName)

  def getRoute: Route = run.getRoute(serviceName)

  def getLatestRevision: Revision = run.getRevision(get.getStatus.getLatestCreatedRevisionName)
}

object CloudRunService {

  // manual tests //
  def main(args: Array[String]): Unit = {
    val run = new CloudRun(
      serviceAccountKey = CloudRun.file2string("/home/dub/.gradle/gcloudServiceAccountKey.json"),
      region = "us-east4",
      logger = LoggerFactory.getLogger(classOf[CloudRunService])
    )
    val service = run.serviceForYaml("/home/dub/OpenTorah/opentorah.org/collector/service.yaml")

//    println(CloudRun.json2yaml(service.get))
//    println(CloudRun.json2yaml(service.getLatestRevision))
//    println(CloudRun.json2yaml(service.redeploy(service.get)))
    println(CloudRun.json2yaml(run.getRoute("collector")))
  }
}
