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

  def fetchData(): JsValue = {
    Application.currentGame.map { game =>
      val ellapsed = (System.currentTimeMillis() - Application.start.get()) / 1000
      Json.obj(
        "waiting" -> game.waitingPlayers.size(),
        "playing" -> game.activePlayers.size(),
        "totalbullets" -> Application.padCounterFire.get(),
        "totalcommands" -> Application.padCounterMoves.get(),
        "ellapsedtime" -> ellapsed,
        "bulletspersec" -> Application.padCounterFire.get() / ellapsed,
        "commandspersec" -> Application.padCounterMoves.get() / ellapsed
      )
    }.getOrElse(Json.obj(
      "waiting" -> 0,
      "playing" -> 0,
      "totalbullets" -> 0,
      "totalcommands" -> 0,
      "ellapsedtime" -> 0,
      "bulletspersec" -> 0,
      "commandspersec" -> 0
    ))
  }
}
