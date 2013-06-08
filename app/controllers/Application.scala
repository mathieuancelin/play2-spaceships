package controllers

import play.api._
import play.api.mvc._
import play.api.data.Forms._
import play.api.data._

import play.api.Play.current
import play.api.libs._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import java.util.concurrent._
import scala.concurrent.stm._
import play.api.cache._
import play.api.libs.json._
import core._
import akka.actor._
import java.util.concurrent.ConcurrentHashMap
import scala.collection.immutable.{ List => JList }
import java.util.concurrent.atomic.AtomicLong

object Application extends Controller {

    val usernameForm = Form( "username" -> text )  
    val actionForm = Form( "message" -> text )  
    val sizeForm = Form( tuple( "width" -> text, "height" -> text ) )

    val players = Concurrent.broadcast[JsValue]
    val bullets = Concurrent.broadcast[JsValue]

    val playersChannel = players._2
    val bulletsChannel = bullets._2

    val playersEnumerator = players._1
    val bulletsEnumerator = bullets._1

    var sinkEnumerator = Enumerator.eof[JsValue]
    var sinkIteratee = Iteratee.foreach[JsValue] { _ => Logger("Application").info("Message on sink Iteratee ...") }

    var currentGame = Option( Game( playersEnumerator, playersChannel ).start() )
    val start = new AtomicLong(System.currentTimeMillis())

    def resetStats() = Action {
      start.set(System.currentTimeMillis())
      Ok
    }

    def restartGame() = Action {
        val oldGame = currentGame
        currentGame.map { game =>
            game.stop()
        }
        currentGame = Option( Game( playersEnumerator, playersChannel ).start() )
        playersChannel.push(Json.obj("action" -> "restart"))
        oldGame.map { game =>
            Game.resetPlayers(game)
        }
        padCounterFire.set(0)
        padCounterMoves.set(0)
        start.set(System.currentTimeMillis())
        Ok
    }

    def index() = Action { implicit request =>
        Ok( views.html.board() )    
    }

    def mobileStart() = Action { implicit request =>
        Ok( views.html.mobilestart() )
    }

    def mobilePad(username: String) = Action { implicit request =>
        currentGame.map { game =>
            val finalUsername = username + "-" + System.nanoTime()
            Logger("Application").info("New player '" + finalUsername + "'")
            Ok( views.html.control( finalUsername ) )
        }.getOrElse(
            Redirect( routes.Application.mobileStart() )
        )
    }

    def startGame() = Action { implicit request =>
        usernameForm.bindFromRequest.fold (
            formWithErrors => BadRequest( "You need to post a 'username' value!" ),
            { username =>
                currentGame.map { game =>
                    Redirect("/mobile/" + Game.sanitizeUsername( username ) + "/pad")
                }.getOrElse(
                    Redirect( routes.Application.mobileStart() )
                )
            } 
        )
    }

    def monitoringSSE() = Action { implicit request =>
      Ok.feed( Monitoring.monitoringEnumerator.through( EventSource() ) ).as( "text/event-stream" )
    }

    def playersSSE() = Action { implicit request =>
        Ok.feed( playersEnumerator.through( EventSource() ) ).as( "text/event-stream" )
    }

    def bulletsSSE() = Action { implicit request =>
        Ok.feed( bulletsEnumerator.through( EventSource() ) ).as( "text/event-stream" )
    }

    val padCounterFire = new AtomicLong(0)
    val padCounterMoves = new AtomicLong(0)

    def mobilePadStream( username: String ) = WebSocket.async[JsValue] { request =>
        currentGame.map { game =>
            val out = game.createUser( username )
            game.pushWaitingList( playersChannel )
            val in = Iteratee.foreach[JsValue] ( _ match {
                case message: JsObject => {
                    processInputFromPlayer( username, message )
                }
                case _ => // do nothing
            }).mapDone {
                _ => {
                    Logger("Application").info("Player '" + username + "' disconnected.")
                    currentGame.map { game =>
                        game.kill( username )
                    }
                }
            }
            Promise.pure( ( in, out ) )
        }.getOrElse( 
            Promise.pure( ( sinkIteratee, sinkEnumerator ) ) 
        )
    }

    def processInputFromPlayer( username: String, message: JsValue) = {
        currentGame.map { game =>
            val key = Game.playerUsername( username )
            if ( game.activePlayers.containsKey( username ) ) {
                val actor = game.activePlayers.get( username ).actor
                ( message \ "action" ).as[String] match {
                    case "moving" => {
                      actor ! Move( ( message \ "x" ).as[Double],  ( message \ "y" ).as[Double] )
                      padCounterMoves.incrementAndGet()
                    }
                    case "fire" => {
                      actor ! Shoot( ( message \ "x" ).as[Double],  ( message \ "y" ).as[Double] )
                      padCounterFire.incrementAndGet()
                    }
                    case _ =>
                }
            }
        }
    }
}