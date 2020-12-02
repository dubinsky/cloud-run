package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Revision, Service}

final class CloudRunService private(run: CloudRun, serviceYamlFilePath: String) {

  val service: Service = CloudRun.json2object(classOf[Service], CloudRun.yaml2json(serviceYamlFilePath))

  private def serviceName: String = service.getMetadata.getName

  // container image name of the first container!
  def containerImage: String = service.getSpec.getTemplate.getSpec.getContainers.get(0).getImage

  def getService: Service = run.getService(serviceName)

  def createService: Service = run.createService(service)

  def replaceService: Service = run.replaceService(serviceName, service)

  def getLatestRevision: Revision =
    run.getRevision(getService.getStatus.getLatestCreatedRevisionName)
}

object CloudRunService {

  def apply(
    serviceAccountKey: String,
    region: String,
    serviceYamlFilePath: String
 ): CloudRunService = new CloudRunService(
    run = new CloudRun(
      serviceAccountKey,
      region
    ),
    serviceYamlFilePath
  )

  // manual tests //
  def main(args: Array[String]): Unit = {
    val service: CloudRunService = CloudRunService(
      serviceAccountKey = CloudRun.file2string("/home/dub/.gradle/gcloudServiceAccountKey.json"),
      region = "us-east4",
      serviceYamlFilePath = "service.yaml"
    )

    println(CloudRun.json2yaml(service.getLatestRevision))
  }
}
