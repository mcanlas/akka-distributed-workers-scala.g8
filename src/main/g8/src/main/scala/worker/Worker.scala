package worker

import java.util.UUID

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor._
import akka.cluster.singleton.{ClusterSingletonProxy, ClusterSingletonProxySettings}

import scala.concurrent.duration._

object Worker {

  def props(workExecutorProps: Props, registerInterval: FiniteDuration = 10.seconds): Props =
    Props(new Worker(workExecutorProps, registerInterval))

  case class WorkComplete(result: Any)
}

class Worker(workExecutorProps: Props, registerInterval: FiniteDuration)
  extends Actor with ActorLogging {
  import MasterWorkerProtocol._
  import Worker._

  val workerId = UUID.randomUUID().toString

  import context.dispatcher

  // TODO extract proxy details to a single place (now repeated)
  val masterProxy = context.actorOf(
    ClusterSingletonProxy.props(
      settings = ClusterSingletonProxySettings(context.system).withRole("backend"),
      singletonManagerPath = "/user/master"
    ),
    name = "masterProxy")

  val registerTask = context.system.scheduler.schedule(0.seconds, registerInterval, masterProxy, RegisterWorker(workerId))

  val workExecutor = context.watch(context.actorOf(workExecutorProps, "exec"))

  var currentWorkId: Option[String] = None
  def workId: String = currentWorkId match {
    case Some(workId) => workId
    case None         => throw new IllegalStateException("Not working")
  }

  override def supervisorStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: DeathPactException           => Stop
    case _: Exception =>
      currentWorkId foreach { workId => masterProxy ! WorkFailed(workerId, workId) }
      context.become(idle)
      Restart
  }

  override def postStop(): Unit = registerTask.cancel()

  def receive = idle

  def idle: Receive = {
    case WorkIsReady =>
      masterProxy ! WorkerRequestsWork(workerId)

    case Work(workId, job) =>
      log.info("Got work: {}", job)
      currentWorkId = Some(workId)
      workExecutor ! job
      context.become(working)
  }

  def working: Receive = {
    case WorkComplete(result) =>
      log.info("Work is complete. Result {}.", result)
      masterProxy ! WorkIsDone(workerId, workId, result)
      context.setReceiveTimeout(5.seconds)
      context.become(waitForWorkIsDoneAck(result))

    case _: Work =>
      log.info("Yikes. Master told me to do work, while I'm working.")
  }

  def waitForWorkIsDoneAck(result: Any): Receive = {
    case Ack(id) if id == workId =>
      masterProxy ! WorkerRequestsWork(workerId)
      context.setReceiveTimeout(Duration.Undefined)
      context.become(idle)
    case ReceiveTimeout =>
      log.info("No ack from master, retrying")
      masterProxy ! WorkIsDone(workerId, workId, result)
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(`workExecutor`) => context.stop(self)
    case WorkIsReady                =>
    case _                          => super.unhandled(message)
  }


}