package core

import play.api.Play.current
import play.api.libs._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import java.util.concurrent._
import scala.concurrent.stm._
import play.api.libs.json._
import akka.actor._
import java.util._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent._
import scala.concurrent.duration._
import scala.collection.immutable.{ List => JList }
import controllers._
import scala.collection.JavaConversions._
import play.api.libs.concurrent.Execution.Implicits._

case class Player( username: String, spaceShip: SpaceShip, enumerator: PushEnumerator[JsValue], actor: ActorRef )

class Game( enumerator: PushEnumerator[JsValue] ) {

    val system = ActorSystem("CurrentGameSystem")

    var activePlayers = new ConcurrentHashMap[String, Player]

    var waitingPlayers = new ConcurrentHashMap[String, Player]

    var waitingPlayersName = new ArrayList[String]

    val shooter = system.actorOf(Props(new ShootActor(Option(this))), name = "currentshootactor")

    var XMAX = 1024//600
    var YMAX = 600//1000
    var cancellable: Cancellable = _

    def start() = {
        cancellable = system.scheduler.schedule(0 millisecond, 38 milliseconds) { //28 rapide
            shooter ! Tick()
            //system.eventStream.publish( Tick() )  
        }
        this
    }

    def stop() = {
        cancellable.cancel()
        shooter ! PoisonPill
        system.shutdown()
    }

    def createUser( username: String ):PushEnumerator[JsValue] = {
        if ( activePlayers.size < Game.playerMax) { 
            createUserIfAbsent( username, "play", activePlayers )
        } else {
            if (activePlayers.containsKey()) {
                return activePlayers.get( username ).enumerator
            } 
        	if ( !waitingPlayersName.contains( username ) ) {
        		waitingPlayersName.add( username )
        	}
            createUserIfAbsent( username, "wait", waitingPlayers ) 
        }
    }

    def createUserIfAbsent( username: String, action: String, map: ConcurrentHashMap[String, Player] ) = {
    	if ( !map.containsKey( username ) ) {
            val key = Game.playerUsername( username )
            val ship = new SpaceShip( new Random().nextInt(XMAX - 50) + 50, new Random().nextInt(YMAX - 50) + 50 )
            val actor = system.actorOf(Props(new ActorPlayer(username, 
                spaceShip = ship, currentGame = Option( this ))), name = key)
            map.put( username, Player( username, ship, Enumerator.imperative[JsValue]( ), actor ) )
        } 
        val pushEnum = map.get( username ).enumerator
        system.scheduler.scheduleOnce(new FiniteDuration(200, TimeUnit.MILLISECONDS)) {
            pushEnum.push( JsObject( JList( "action" -> JsString( action ) ) ) )
        }
        pushEnum
    }

    def kill( username: String ) = {
    	val out = Option( activePlayers.get( username ) )
        out.map { player =>
            player.enumerator.push( JsObject( JList( "action" -> JsString( "kill" ) ) ) )
            enumerator.push( JsObject( JList( "action" -> JsString( "kill" ), "name" -> JsString( username ) ) ) )
            player.actor ! Kill( 0, 0 )
            player.actor ! PoisonPill
            activePlayers.remove( username )
            if (!waitingPlayers.isEmpty()) {
                val waitingPlayer = waitingPlayers.get( waitingPlayersName.iterator().next() )
                waitingPlayers.remove( username )
                waitingPlayersName.remove( username )
                activePlayers.put( waitingPlayer.username, waitingPlayer )
                waitingPlayer.enumerator.push( JsObject( JList( "action" -> JsString( "play" ) ) ) )
            }
        }
        pushWaitingList( Application.playersEnumerator )
        if (out.isDefined && activePlayers.isEmpty() && waitingPlayers.isEmpty()) {
            Application.currentGame = None
            "nowinner"
        } else 
        if (out.isDefined && activePlayers.size == 1 && waitingPlayers.isEmpty()) {
        //if (activePlayers.size == 1 && waitingPlayers.isEmpty()) {
            // stop game and call winner
            val p = activePlayers.entrySet().iterator().next().getValue()
            p.enumerator.push( JsObject( JList( "action" -> JsString( "win" ) ) ) )
            Application.currentGame = None
            "winner:" + p.username
        } else { 
            "continue"
        }
    }

    def pushWaitingList( enumerator: PushEnumerator[JsValue] ) = {
        var waiting = JList[JsObject]( )
        for ( player <- waitingPlayers.values() ) {
            waiting = waiting :+ JsObject( JList( "player" -> JsString( player.username ) ) )
        }
        enumerator.push( JsObject( JList( "action" -> JsString( "waitinglist" ), "players" -> JsArray( waiting ) ) ) )
    }
}

object Game {

    val playerMax = 20

    val anon = "Anon"

    var counter = 0

    def playerUsername( username: String ) = {
        "playerWithUsername-" + username
    }

    def apply( enumerator: PushEnumerator[JsValue] ): Game = {
        val game = new Game( enumerator ) 
        game
    }

    def sanitizeUsername( username: String) = {
        val sane = username.replace(" ", "").replace("-", "").replaceAll("[^a-zA-Z0-9]", "").trim()
        if (sane.isEmpty()) {
            counter = counter + 1
            anon + counter
        } else {
            sane
        }
    }

    def resetPlayers(game: Game) = {
        for(player <- game.waitingPlayers.values) {
            player.enumerator.push(Json.obj("action" -> "restart"))
        }
        for(player <- game.activePlayers.values) {
            player.enumerator.push(Json.obj("action" -> "restart"))
        }
    }
}