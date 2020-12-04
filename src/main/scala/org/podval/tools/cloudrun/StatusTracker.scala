package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.GoogleCloudRunV1Condition
import org.slf4j.Logger
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

// see https://github.com/twistedpair/google-cloud-sdk/blob/9d6a1cf6238702560b22944089355eff06b5c216/google-cloud-sdk/lib/googlecloudsdk/api_lib/util/waiter.py
object StatusTracker {

  final case class Stage(
    name: String,
    getConditions: () => java.util.List[GoogleCloudRunV1Condition]
  )

  private val indent: String = "  "

  def track(
    logger: Logger,
    preStartSleepMs: Int = 1000,
    sleepMs: Int = 500,
    stages: Seq[Stage]
  ): Unit = {
    if (preStartSleepMs > 0) Thread.sleep(preStartSleepMs)
    for (stage <- stages) track(logger, sleepMs, stage)
  }

  private def track(
    logger: Logger,
    sleepMs: Int,
    stage: Stage
  ) {
    var done: Boolean = false
    var previous: Map[String, GoogleCloudRunV1Condition] = Map.empty
    logger.warn(indent + stage.name)

    while (!done) {
      val current: Map[String, GoogleCloudRunV1Condition] = Option(stage.getConditions())
        .getOrElse(java.util.Collections.emptyList())
        .asScala
        .map(condition => condition.getType -> condition)
        .toMap

      val newMessages: Set[String] = current.values
        .filterNot(condition => previous.get(condition.getType).contains(condition))
        .filterNot(isRetry)
        .flatMap(condition => Option(condition.getMessage))
        .toSet
        .map(message => indent + indent + message)

      if (newMessages.nonEmpty) logger.warn(newMessages.mkString("\n"))

      done = current.nonEmpty && current.values.filterNot(isRetry).forall(_.getStatus == "True")

      previous = current

      if (!done) Thread.sleep(sleepMs)
    }

    // TODO is it really done if Cloud Run is retrying *something* in 10 minutes?
    //val retry: Option[GoogleCloudRunV1Condition] = previous.get("Retry")
    //retry.foreach(retry => logger.warn(toString(retry)))
  }

  private def isRetry(condition: GoogleCloudRunV1Condition): Boolean = condition.getType == "Retry"

  private def toString(condition: GoogleCloudRunV1Condition): String = {
    val message: Option[String] = Option(condition.getMessage)
    val reason: Option[String] = Option(condition.getReason)
    val string: String =
      message.map(message => s"[$message] ").getOrElse("") +
      reason.map(reason => s"($reason)").getOrElse("")
    s"${condition.getType}=${condition.getStatus} $string"
  }
}
