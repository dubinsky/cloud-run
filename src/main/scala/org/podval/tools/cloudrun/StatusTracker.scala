package org.podval.tools.cloudrun

import com.google.api.services.run.v1.model.GoogleCloudRunV1Condition
import scala.jdk.CollectionConverters.IterableHasAsScala

// see https://github.com/twistedpair/google-cloud-sdk/blob/9d6a1cf6238702560b22944089355eff06b5c216/google-cloud-sdk/lib/googlecloudsdk/api_lib/util/waiter.py
final class StatusTracker(
  log: String => Unit,
  preStartSleepMs: Int = 500,
  sleepMs: Int = 500,
  stages: Seq[StatusTracker.Stage]
) {
  def track(): Unit = {
    if (preStartSleepMs > 0) Thread.sleep(preStartSleepMs)

    val threads: Seq[Thread] = for (stage <- stages)
      yield new Thread(() => new StageTracker(stage).track())

    for (thread <- threads) thread.start()
    for (thread <- threads) thread.join()
  }

  private final class StageTracker(stage: StatusTracker.Stage) {
    private var done: Boolean = false
    private var previous: Map[String, GoogleCloudRunV1Condition] = Map.empty

    def track(): Unit = while (!done) {
      val current: Map[String, GoogleCloudRunV1Condition] = stage.poll()

      val newMessages: Set[String] = StatusTracker.getNewMessages(previous, current)
        .map(message => stage.name + ": " + message)

      if (newMessages.nonEmpty) log(newMessages.mkString("\n"))

      // TODO is it really done if Cloud Run is retrying *something* in 10 minutes?
      //val retry: Option[GoogleCloudRunV1Condition] = previous.get("Retry")
      //retry.foreach(retry => logger.warn(StatusTracker.toString(retry)))
      done = current.nonEmpty && current.values
        .filterNot(StatusTracker.isRetry)
        .forall(StatusTracker.isDone)

      previous = current

      if (!done) Thread.sleep(sleepMs)
    }
  }
}

object StatusTracker {

  final case class Stage(
    name: String,
    getConditions: () => java.util.List[GoogleCloudRunV1Condition]
  ) {
    def poll(): Map[String, GoogleCloudRunV1Condition] = Option(getConditions())
      .getOrElse(java.util.Collections.emptyList())
      .asScala
      .map(condition => condition.getType -> condition)
      .toMap
  }

  private def isRetry(condition: GoogleCloudRunV1Condition): Boolean = condition.getType   == "Retry"

  private def isDone (condition: GoogleCloudRunV1Condition): Boolean = condition.getStatus == "True"

  private def getNewMessages(
    previous: Map[String, GoogleCloudRunV1Condition],
    current: Map[String, GoogleCloudRunV1Condition]
  ): Set[String] = current.values
    .filterNot(isRetry)
    .filterNot(condition => previous.get(condition.getType).contains(condition))
    .filterNot(_.getMessage == null)
    .map(_.getMessage)
    .toSet

//  private def toString(condition: GoogleCloudRunV1Condition): String = {
//    val message: Option[String] = Option(condition.getMessage)
//    val reason: Option[String] = Option(condition.getReason)
//    val string: String =
//      message.map(message => s"[$message] ").getOrElse("") +
//      reason.map(reason => s"($reason)").getOrElse("")
//    s"${condition.getType}=${condition.getStatus} $string"
//  }
}
