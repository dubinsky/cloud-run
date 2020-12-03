package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.{Revision, Service, Status}

final class CloudRunService(run: CloudRun, val service: Service) {

  private def serviceName: String = service.getMetadata.getName

  // container image name of the first container!
  def containerImage: String = service.getSpec.getTemplate.getSpec.getContainers.get(0).getImage

  def create: Service = run.createService(service)

  def get: Service = run.getService(serviceName)

  def replace: Service = run.replaceService(serviceName, service)

  // TODO why does gcloud add three-letter suffix?
  // TODO what field does gcloud use to force redeployment?
  def redeploy(previous: Service): Service = {
    val serviceAdjusted = service.clone()
    val nextGeneration: Int = previous.getMetadata.getGeneration + 1
    serviceAdjusted.getSpec.getTemplate.getMetadata.setName(f"$serviceName-$nextGeneration%05d")
    run.replaceService(serviceName, serviceAdjusted)
  }

  def delete: Status = run.deleteService(serviceName)

  def getLatestRevision: Revision = run.getRevision(get.getStatus.getLatestCreatedRevisionName)
}

object CloudRunService {

  // manual tests //
  def main(args: Array[String]): Unit = {
    val service: CloudRunService = new CloudRun(
      serviceAccountKey = CloudRun.file2string("/home/dub/.gradle/gcloudServiceAccountKey.json"),
      region = "us-east4"
    ).serviceForYaml("/home/dub/OpenTorah/opentorah.org/collector/service.yaml")

    //    println(CloudRun.json2yaml(service.getLatestRevision))
    println(CloudRun.json2yaml(service.redeploy(service.get)))
  }
}
