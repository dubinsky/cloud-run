package org.podval.tools.cloudrun

import org.gradle.api.{DefaultTask, Project, Task}

open class CloudRunTask extends DefaultTask:

  def dependsOnJibTask(jibTaskName: String): Unit = getProject.afterEvaluate((project: Project) =>
    if Util.getJib(project).isDefined then dependsOn(project.getTasks.getByPath(jibTaskName))
  )

  protected final def extension: CloudRunExtension = getProject.getExtensions.getByType(classOf[CloudRunExtension])

  protected final def cloudRunService: CloudRunService = CloudRunService(
    serviceAccountKey = extension.serviceAccountKey,
    region = extension.getRegionValue,
    service = extension.service,
    log = getProject.getLogger
  )
