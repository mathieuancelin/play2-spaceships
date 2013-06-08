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
import play.api.libs.iteratee.Concurrent.Channel

case class Player( username: String, spaceShip: SpaceShip, enumerator: Enumerator[JsValue], channel: Channel[JsValue], actor: ActorRef )

class Game( enumerator: Enumerator[JsValue], channel: Channel[JsValue] ) {

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
        }
        this
    }

    def stop() = {
        cancellable.cancel()
        shooter ! PoisonPill
        system.shutdown()
    }

    def createUser( username: String ): Enumerator[JsValue] = {
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
            val concurrent = Concurrent.broadcast[JsValue]
            map.put( username, Player( username, ship, concurrent._1, concurrent._2, actor ) )
        } 
        val pushEnum = map.get( username ).channel
        system.scheduler.scheduleOnce(new FiniteDuration(200, TimeUnit.MILLISECONDS)) {
            pushEnum.push( JsObject( JList( "action" -> JsString( action ) ) ) )
        }
        //pushEnum
        map.get( username ).enumerator
    }

    def kill( username: String ) = {
    	val out = Option( activePlayers.get( username ) )
        out.map { player =>
            player.channel.push( JsObject( JList( "action" -> JsString( "kill" ) ) ) )
            channel.push( JsObject( JList( "action" -> JsString( "kill" ), "name" -> JsString( username ) ) ) )
            player.actor ! Kill( 0, 0 )
            player.actor ! PoisonPill
            activePlayers.remove( username )
            if (!waitingPlayers.isEmpty()) {
                val waitingPlayer = waitingPlayers.get( waitingPlayersName.iterator().next() )
                waitingPlayers.remove( username )
                waitingPlayersName.remove( username )
                activePlayers.put( waitingPlayer.username, waitingPlayer )
                waitingPlayer.channel.push( JsObject( JList( "action" -> JsString( "play" ) ) ) )
            }
        }
        pushWaitingList( Application.playersChannel )
        if (out.isDefined && activePlayers.isEmpty() && waitingPlayers.isEmpty()) {
            Application.currentGame = None
            Application.playersChannel.push(Json.obj("action" -> "nowinner"))
            "nowinner"
        } else 
        if (out.isDefined && activePlayers.size == 1 && waitingPlayers.isEmpty()) {
            val p = activePlayers.entrySet().iterator().next().getValue()
            p.channel.push( JsObject( JList( "action" -> JsString( "win" ) ) ) )
            Application.currentGame = None
            Application.playersChannel.push(Json.obj("action" -> "winner", "username" -> p.username.split("-").head))
            "winner:" + p.username
        } else { 
            "continue"
        }
    }

    def pushWaitingList( enumerator: Channel[JsValue] ) = {
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

    def apply( enumerator: Enumerator[JsValue], channel: Channel[JsValue] ): Game = {
        val game = new Game( enumerator, channel )
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
            player.channel.push(Json.obj("action" -> "restart"))
        }
        for(player <- game.activePlayers.values) {
            player.channel.push(Json.obj("action" -> "restart"))
        }
    }
}