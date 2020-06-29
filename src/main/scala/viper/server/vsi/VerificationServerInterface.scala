package viper.server.vsi

import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.Http
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.util.Timeout
import org.reactivestreams.Publisher

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class JobHandle(controller_actor: ActorRef,
                     queue: SourceQueueWithComplete[Letter],
                     publisher: Publisher[Letter])

class JobPool(val MAX_ACTIVE_JOBS: Int = 3){
  var jobHandles: mutable.Map[Int, Future[JobHandle]] = mutable.Map[Int, Future[JobHandle]]()
  private var _nextJobId: Int = 0

  def newJobsAllowed = jobHandles.size < MAX_ACTIVE_JOBS

  /** Creates a Future of a JobHandle, representing the new job
    *
    * For the next available job ID the function job_executor will set up a JobActor that will start
    * a verification process and thereby eventually produce the JobHandle that's returned as a Future.
    * */
  def bookNewJob(job_executor: Int => Future[JobHandle]): (Int, Future[JobHandle]) = {
    val new_jid = _nextJobId
    jobHandles(new_jid) = job_executor(new_jid)
    _nextJobId = _nextJobId + 1
    (new_jid, jobHandles(new_jid))
  }

  def discardJob(jid: Int): mutable.Map[Int, Future[JobHandle]] = {
    jobHandles -= jid
  }

  def lookupJob(jid: Int): Option[ Future[JobHandle] ] = {
    jobHandles.get(jid)
  }
}


trait VerificationServerInterface {

  case class VerificationJobHandler(id: Int)

  implicit val system: ActorSystem = ActorSystem("Main")
  implicit val executionContext = ExecutionContext.global
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val jobs = new JobPool()

  def createTask(queueActor: ActorRef, task:VerificationTask): Thread = {
    task.setQueueActor(queueActor)
    new Thread(task)
  }

  def initializeVerificationProcess(task:VerificationTask): VerificationJobHandler = {
    if (jobs.newJobsAllowed) {
      def createJob(new_jid: Int): Future[JobHandle] = {
        implicit val askTimeout: Timeout = Timeout(5000 milliseconds)
        val job_actor = system.actorOf(JobActor.props(new_jid), s"job_actor_$new_jid")
        val (queue, publisher) = Source.queue[Letter](10000, OverflowStrategy.backpressure)
                                       .toMat(Sink.asPublisher(false))(Keep.both)
                                       .run()
        val reportingActor = system.actorOf(QueueActor.props(new_jid, queue), s"queue_actor_$new_jid")
        val backendTask = createTask(reportingActor, task)
        val answer = job_actor ? VerificationProtocol.Verify(backendTask, queue, publisher)
        val new_job_handle: Future[JobHandle] = answer.mapTo[JobHandle]
        new_job_handle
      }
      val (id, jobHandle) = jobs.bookNewJob(createJob)
      VerificationJobHandler(id)
    } else {
      println(s"the maximum number of active verification jobs are currently running ($jobs.MAX_ACTIVE_JOBS).")
      VerificationJobHandler(-1) // Not able to create a new JobHandle
    }
  }


  protected var _termActor: ActorRef = _

  object Terminator {
    case object Exit
    case class WatchJobQueue(jid: Int, handle: JobHandle)

    def props(bindingFuture: Future[Http.ServerBinding]): Props = Props(new Terminator(Some(bindingFuture)))
    def props(): Props = Props(new Terminator(None))
  }

  class Terminator(bindingFuture: Option[Future[Http.ServerBinding]]) extends Actor {

    override def receive: PartialFunction[Any, Unit] = {
      case Terminator.Exit =>
        bindingFuture match {
          case Some(future) =>
            future
              .flatMap(_.unbind()) // trigger unbinidng from the port
              .onComplete(_ => {
                system.terminate() // and shutdown when done
              })
          case None =>
            system.terminate() // shutdown
        }
      case Terminator.WatchJobQueue(jid, handle) =>
        val queue_completion_future: Future[Done] = handle.queue.watchCompletion()
        queue_completion_future.onComplete( {
          case Failure(e) =>
            println(s"Terminator detected failure in job #$jid: $e")
            throw e
          case Success(_) =>
            jobs.discardJob(jid)
            println(s"Terminator deleted job #$jid")
        })
    }
  }


  // --- Actor: MainActor ---

  object JobActor {
    def props(id: Int): Props = Props(new JobActor(id))
  }

  class JobActor(private val id: Int) extends Actor {

    //Agnostic
    private var _verificationTask: Thread = _

    // blocking
    //Agnostic
    private def interrupt: Boolean = {
      if (_verificationTask != null && _verificationTask.isAlive) {
        _verificationTask.interrupt()
        _verificationTask.join()
        println(s"Job #$id has been successfully interrupted.")
        return true
      }
      false
    }
    //Agnostic
    private def resetVerificationTask(): Unit = {
      if (_verificationTask != null && _verificationTask.isAlive) {
        _verificationTask.interrupt()
        _verificationTask.join()
      }
      _verificationTask = null
    }

    override def receive: PartialFunction[Any, Unit] = {
      //Agnostic
      case VerificationProtocol.Stop(call_back_needed) =>
        val did_I_interrupt = interrupt
        if (call_back_needed) {
          // If a callback is expected, then the caller must decide when to kill the actor.
          if (did_I_interrupt) {
            sender ! s"Job #$id has been successfully interrupted."
          } else {
            sender ! s"Job #$id has already been finalized."
          }
        }
      case VerificationProtocol.Verify(task, queue, publisher) =>
        resetVerificationTask()
        sender ! startJob(task, queue, publisher)
      case msg =>
        throw new Exception("Main Actor: unexpected message received: " + msg)
    }

    private def startJob(task: Thread, queue: SourceQueueWithComplete[Letter], publisher: Publisher[Letter]): JobHandle = {
      _verificationTask = task
      _verificationTask.start()
      println(s"Starting job #$id...")
      JobHandle(self, queue, publisher)
    }
  }


  // --- Actor: QueueActor ---

  object QueueActor {
    def props(jid: Int, queue: SourceQueueWithComplete[Letter]): Props = Props(new QueueActor(jid, queue))
  }

  class QueueActor(jid: Int, queue: SourceQueueWithComplete[Letter]) extends Actor {

    override def receive: PartialFunction[Any, Unit] = {
      case TaskProtocol.ClientRequest =>
      case TaskProtocol.ServerReport(msg) =>
        val offer_status = queue.offer(msg)
        sender() ! offer_status
      case TaskProtocol.FinalServerReport(success) =>
        queue.complete()
        if ( success )
          println(s"Job #$jid has been completed successfully.")
        else
          println(s"Job #$jid has been completed ERRONEOUSLY.")
        self ! PoisonPill
      case _ =>
    }
  }

  def stop(): Unit = {
    getInterruptFutureList() onComplete {
      case Success(_) =>
        _termActor ! Terminator.Exit
        println(s"shutting down...")
      case Failure(err_msg) =>
        println(s"Interrupting one of the verification threads timed out: $err_msg")
        _termActor ! Terminator.Exit
        println(s"forcibly shutting down...")
    }
  }

  protected def getInterruptFutureList(): Future[List[String]] = {
    val interrupt_future_list: List[Future[String]] = jobs.jobHandles map { case (jid, handle_future) =>
      handle_future.flatMap {
        case JobHandle(actor, _, _) =>
          implicit val askTimeout: Timeout = Timeout(1000 milliseconds)
          (actor ? VerificationProtocol.Stop(true)).mapTo[String]
      }
    } toList
    val overall_interrupt_future: Future[List[String]] = Future.sequence(interrupt_future_list)
    overall_interrupt_future
  }
}

abstract class VerificationTask()(implicit val executionContext: ExecutionContext) extends Runnable {

  protected var q_actor: ActorRef = _

  def setQueueActor(actor: ActorRef): Unit = q_actor = actor

  /** Sends massage to the attached actor.
    *
    * The actor receiving this message offers it to a queue. This offering returns a Future, which will eventually
    * indicate whether or not the offer was successful. This method is blocking, as it waits for the successful
    * completion of such an offer.
    * */
  protected def enqueueMessages(letter:Letter): Unit = {
    var current_offer: Future[QueueOfferResult] = null
    implicit val askTimeout: Timeout = Timeout(5000 milliseconds)
    val answer = q_actor ? letter
    current_offer = answer.flatMap({
      case res: Future[QueueOfferResult] => res
    })
    while(current_offer == null || !current_offer.isCompleted){
      Thread.sleep(10)
    }
  }
}
