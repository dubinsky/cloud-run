package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.GoogleCloudRunV1Condition
import org.slf4j.Logger
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

object StatusTracker {
  // see https://github.com/twistedpair/google-cloud-sdk/blob/9d6a1cf6238702560b22944089355eff06b5c216/google-cloud-sdk/lib/googlecloudsdk/api_lib/util/waiter.py
  def track(
    getConditions: => java.util.List[GoogleCloudRunV1Condition],
    logger: Logger,
    preStartSleepMs: Int = 500,
    sleepMs: Int = 500
  ): Unit = {
    if (preStartSleepMs > 0) Thread.sleep(preStartSleepMs)
    track(getConditions, logger, sleepMs)
  }

  private def track(
    getConditions: => java.util.List[GoogleCloudRunV1Condition],
    logger: Logger,
    sleepMs: Int
  ) {
    var done: Boolean = false
    var previous: Map[String, GoogleCloudRunV1Condition] = Map.empty

    while (!done) {
      val current: Map[String, GoogleCloudRunV1Condition] =
        getConditions.asScala.map(condition => condition.getType -> condition).toMap

      val changes: Iterable[GoogleCloudRunV1Condition] =
        current.values.filterNot(condition => previous.get(condition.getType).contains(condition))

      val newMessages: Set[String] = changes.filterNot(isRetry).flatMap(condition => Option(condition.getMessage)).toSet

//      if (changes.nonEmpty)
//        logger.warn(changes.map(toString).mkString("\n", "\n", "\n"))

      if (newMessages.nonEmpty) logger.warn(newMessages.mkString("  ", "\n  ", ""))

      done = current.values.filterNot(isRetry).forall(_.getStatus == "True")

      previous = current

      if (!done) Thread.sleep(sleepMs)
    }

    // TODO is it really done if Cloud Run is retrying *something* in 10 minutes?
    val retry: Option[GoogleCloudRunV1Condition] = previous.get("Retry")
    retry.foreach(retry => logger.warn(toString(retry)))
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
