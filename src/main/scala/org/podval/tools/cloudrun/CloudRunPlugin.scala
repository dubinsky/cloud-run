package org.podval.tools.cloudrun

import org.gradle.api.tasks.TaskAction
import org.gradle.api.{Plugin, Project}

final class CloudRunPlugin extends Plugin[Project]:

  def apply(project: Project): Unit =
    project.getExtensions.create("cloudRun", classOf[CloudRunExtension])

    project.getTasks.create("cloudRunDeploy"          , classOf[CloudRunPlugin.DeployTask          ])
    project.getTasks.create("cloudRunLocal"           , classOf[RunLocalTask                       ])
    project.getTasks.create("cloudRunDescribe"        , classOf[CloudRunPlugin.DescribeTask        ])
    project.getTasks.create("cloudRunDescribeRevision", classOf[CloudRunPlugin.DescribeRevisionTask])

object CloudRunPlugin:
  // Task classes are not final so that Gradle could create decorated instances.

  class DeployTask extends CloudRunTask:
    setDescription("Deploy the service to Google Cloud Run")
    setGroup("publishing")
    dependsOnJibTask("jib")
    @TaskAction final def execute(): Unit = cloudRunService.deploy()

  class DescribeTask extends CloudRunTask:
    setDescription("Get the Service YAML from Google Cloud Run")
    setGroup("help")
    @TaskAction final def execute(): Unit =
      getProject.getLogger.lifecycle("Latest Service YAML:\n" + Util.json2yaml(cloudRunService.get))

  class DescribeRevisionTask extends CloudRunTask:
    setDescription("Get the latest Revision YAML from Google Cloud Run")
    setGroup("help")
    @TaskAction final def execute(): Unit =
      getProject.getLogger.lifecycle("Latest Revision YAML:\n" + Util.json2yaml(cloudRunService.getLatestRevision))
