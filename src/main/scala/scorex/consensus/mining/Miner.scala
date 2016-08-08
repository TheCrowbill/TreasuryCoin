package scorex.consensus.mining

import akka.actor.{ActorRef, Actor}
import scorex.block.{ConsensusData, TransactionalData}
import scorex.consensus.ConsensusModule
import scorex.consensus.mining.Miner._
import scorex.settings.Settings
import scorex.transaction.Transaction
import scorex.transaction.box.proposition.Proposition
import scorex.utils.ScorexLogging

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Try}

class Miner[P <: Proposition, TX <: Transaction[P, TX], TD <: TransactionalData[TX], CD <: ConsensusData]
(settings: Settings, historySynchronizer: ActorRef, consensusModule: ConsensusModule[P, TX, TD, CD])
  extends Actor with ScorexLogging {

  // BlockGenerator is trying to generate a new block every $blockGenerationDelay. Should be 0 for PoW consensus model.
  val blockGenerationDelay = settings.blockGenerationDelay
  val BlockGenerationTimeLimit = 5.seconds

  var lastTryTime = 0L
  var stopped = false

  private def scheduleAGuess(delay: Option[FiniteDuration] = None): Unit =
    context.system.scheduler.scheduleOnce(delay.getOrElse(blockGenerationDelay), self, GuessABlock)

  scheduleAGuess(Some(0.millis))

  override def receive: Receive = {
    case GuessABlock =>
      stopped = false
      if (System.currentTimeMillis() - lastTryTime >= blockGenerationDelay.toMillis) tryToGenerateABlock()

    case GetLastGenerationTime =>
      sender ! LastGenerationTime(lastTryTime)

    case Stop =>
      stopped = true
  }

  def tryToGenerateABlock(): Unit = Try {

    lastTryTime = System.currentTimeMillis()
    if (blockGenerationDelay > 500.milliseconds) log.info("Trying to generate a new block")
    val blockFuture = consensusModule.generateNextBlock(transactionalModule.wallet)
    val blockOpt = Await.result(blockFuture, BlockGenerationTimeLimit)
    blockOpt.foreach(b => historySynchronizer ! b)
    if (!stopped) scheduleAGuess()
  }.recoverWith {
    case ex =>
      log.error("Failed to generate new block", ex)
      Failure(ex)
  }
}

object Miner {

  case object Stop

  case object GuessABlock

  case object GetLastGenerationTime

  case class LastGenerationTime(time: Long)

}
