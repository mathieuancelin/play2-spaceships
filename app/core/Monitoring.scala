package core

import play.api.libs.iteratee.{Enumerator, Concurrent}
import play.api.libs.json.{Json, JsValue}
import play.api.libs.concurrent.Promise
import play.api.libs.concurrent.Execution.Implicits._
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import controllers.Application

object Monitoring {

  val monitoringEnumerator = Enumerator.generateM[JsValue](
    Promise.timeout(Some(fetchData()), Duration(500, TimeUnit.MILLISECONDS))
  )

  val monitoringConcurrent = Concurrent.broadcast[JsValue](monitoringEnumerator)

  var lastBullets = 0L
  var lastCommands = 0L
  var lastIn = 0L
  var lastOut = 0L

  def fetchData(): JsValue = {
    Application.currentGame.map { game =>
      val ellapsed = (System.currentTimeMillis() - Application.start.get()) / 1000
      val bullets = (Application.padCounterFire.get() - lastBullets) * 2
      val commands = (Application.padCounterMoves.get() - lastCommands) * 2
      val in = ((Application.padCounterFire.get() + Application.padCounterMoves.get()) - lastIn) * 2
      val out = (Application.outCounter.get() - lastOut) * 2
      lastBullets = Application.padCounterFire.get()
      lastCommands = Application.padCounterMoves.get()
      lastIn = lastBullets + lastCommands
      lastOut = Application.outCounter.get()
      Json.obj(
        "waiting" -> game.waitingPlayers.size(),
        "playing" -> game.activePlayers.size(),
        "totalbullets" -> Application.padCounterFire.get(),
        "totalcommands" -> Application.padCounterMoves.get(),
        "ellapsedtime" -> ellapsed,
        "bulletspersec" -> bullets,
        "commandspersec" -> commands,
        "inrequests" -> lastIn,
        "outrequests" -> lastOut,
        "inrequestspersec" -> in,
        "outrequestspersec" -> out
      )
    }.getOrElse(Json.obj(
      "waiting" -> 0,
      "playing" -> 0,
      "totalbullets" -> 0,
      "totalcommands" -> 0,
      "ellapsedtime" -> 0,
      "bulletspersec" -> 0,
      "commandspersec" -> 0,
      "inrequests" -> 0,
      "outrequests" -> 0,
      "inrequestspersec" -> 0,
      "outrequestspersec" -> 0
    ))
  }
}
