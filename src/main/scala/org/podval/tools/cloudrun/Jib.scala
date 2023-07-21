package org.podval.tools.cloudrun

import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.api.{DefaultTask, Project}

object Jib:
  // Extension with the type JibExtension (and the name 'jib')
  // comes from the [JIB plugin](https://github.com/GoogleContainerTools/jib);
  // if it exists, tasks 'jib' and 'jibDockerBuild' exist too.

  val remote: String = "jib"
  
  val localDocker: String = "jibDockerBuild"

  private def get(project: Project): Option[JibExtension] = Option(project.getExtensions.findByType(classOf[JibExtension]))

  def dependsOn(task: DefaultTask, jibTaskName: String): Unit = task.getProject.afterEvaluate((project: Project) =>
    if get(project).isDefined then task.dependsOn(project.getTasks.getByPath(jibTaskName))
  )

  def configure(
    project: Project,
    service: CloudRunService,
    serviceAccountKey: Option[String]
  ): Unit = get(project).foreach { (jibExtension: JibExtension) =>
    def configure(
      name: String,
      getter: JibExtension => String,
      setter: (JibExtension, String) => Unit,
      value: String
    ): Unit = if getter(jibExtension) == null then
      setter(jibExtension, value)
      project.getLogger.info(s"CloudRun: set '$name' to '$value'.", null, null, null)

    configure("jib.to.image",
      _.getTo.getImage, _.getTo.setImage(_), service.containerImage)
    configure("jib.to.auth.username",
      _.getTo.getAuth.getUsername, _.getTo.getAuth.setUsername(_), "_json_key")
    serviceAccountKey.foreach(serviceAccountKey =>
      configure("jib.to.auth.password",
        _.getTo.getAuth.getPassword, _.getTo.getAuth.setPassword(_), serviceAccountKey))
  }

