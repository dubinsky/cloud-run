package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.Service
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.{Input, TaskAction}
import org.gradle.process.ExecSpec
import ServiceExtender.*

import scala.jdk.CollectionConverters.{ListHasAsScala, SeqHasAsJava}

class RunLocalTask extends CloudRunTask:
  setDescription("Run Cloud Run service in the local Docker")
  setGroup("publishing")

  private val additionalOptions: ListProperty[String] = getProject.getObjects.listProperty(classOf[String])
  @Input def getAdditionalOptions: ListProperty[String] = additionalOptions
  additionalOptions.set(Seq.empty[String].asJava)

  dependsOnJibTask("jibDockerBuild")

  @TaskAction final def execute(): Unit =
    val service: Service = extension.service
    val additionalOptions: Seq[String] = this.additionalOptions.get.asScala.toSeq
    val port: Int = service.containerPort.getOrElse(8080)

    val commandLine: Seq[String] =
      Seq(
        "docker", "run",
        "--rm",
        "--name"   , service.name,
        "--cpus"   , service.cpuFloat,
        "--memory" , service.memory
      ) ++
      Util.list("--label", service.labels) ++
      Util.list("--env"  , service.env   ) ++
      Seq(
        "--env"    , s"PORT=$port",
        "--publish", s"$port:$port"
      ) ++
      additionalOptions ++
      Seq(
        service.containerImage
      ) ++
      // TODO use --entrypoint for the first string (see
      // https://docs.docker.com/engine/reference/run/#entrypoint-default-command-to-execute-at-runtime)?
      service.command.toSeq.flatten ++
      service.args.toSeq.flatten

    getLogger.lifecycle(s"Running: ${commandLine.mkString(" ")}")
    // TODO use opentorah-util?
    getProject.exec((execSpec: ExecSpec) => execSpec.setCommandLine(commandLine.asJava))
