package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.Service
import org.gradle.api.tasks.TaskAction
import org.gradle.api.{DefaultTask, Plugin, Project}

final class CloudRunPlugin extends Plugin[Project]:

  def apply(project: Project): Unit =
    CloudRunExtension.create(project)

    project.getTasks.create("cloudRunDeploy", classOf[CloudRunPlugin.DeployTask])
    project.getTasks.create("cloudRunLocal", classOf[RunLocalTask])
    project.getTasks.create("cloudRunDescribe", classOf[CloudRunPlugin.DescribeTask])
    project.getTasks.create("cloudRunDescribeRevision", classOf[CloudRunPlugin.DescribeRevisionTask])

object CloudRunPlugin:

  // Task classes are not final so that Gradle could create decorated instances.

  class DeployTask extends DefaultTask:
    setDescription("Deploy the service to Google Cloud Run")
    setGroup("publishing")

    Util.dependsOnJib(this, "jib")

    @TaskAction final def execute(): Unit = getService(getProject).deploy()

  class DescribeTask extends DefaultTask:
    setDescription("Get the Service YAML from Google Cloud Run")
    setGroup("help")

    @TaskAction final def execute(): Unit = getProject.getLogger.lifecycle(
      "Latest Service YAML:\n" + Util.json2yaml(getService(getProject).get)
    )

  class DescribeRevisionTask extends DefaultTask:
    setDescription("Get the latest Revision YAML from Google Cloud Run")
    setGroup("help")

    @TaskAction final def execute(): Unit = getProject.getLogger.lifecycle(
      "Latest Revision YAML:\n" + Util.json2yaml(getService(getProject).getLatestRevision)
    )

  private def getService(project: Project): CloudRunService =
    val extension: CloudRunExtension = CloudRunExtension.get(project)
    CloudRunService(
      run = CloudRun(
        serviceAccountKey = extension.serviceAccountKey.getOrElse(throw IllegalArgumentException("No service account key!")),
        region = extension.getRegionValue,
        log = project.getLogger
      ),
      service = extension.service
    )
