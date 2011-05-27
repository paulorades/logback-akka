package com.mojolly.logback

import org.specs2.Specification
import ch.qos.logback.classic.{LoggerContext}
import ch.qos.logback.classic.joran.JoranConfigurator
import collection.mutable.ListBuffer
import reflect.BeanProperty
import ch.qos.logback.core.{Layout, AppenderBase}
import ch.qos.logback.core.util.StatusPrinter
import org.multiverse.api.latches.StandardLatch
import ch.qos.logback.classic.spi.ILoggingEvent
import java.util.concurrent.{TimeUnit, ConcurrentSkipListSet}
import akka.actor.{ActorRef, Actor}
import org.specs2.specification.{Before, Around}

object StringListAppender {
  val messages = new ConcurrentSkipListSet[String]()
}
class StringListAppender[E] extends AppenderBase[E] {
  val messages = new ListBuffer[String]
  @BeanProperty var layout: Layout[E] = _

  override def start() {
    Option(layout).filter(_.isStarted).foreach(_ => super.start())
  }

  def append(p1: E) {
    synchronized { messages += layout.doLayout(p1) }
  }
}

class ActorAppenderSpec extends Specification { def is =

  "An actor appender for logback should" ^
    "log to the child appender" ! withStringListAppender(logToChildAppenders) ^
    "log to a listener actor" ! logToListenerActor ^ end

  def logToListenerActor = {
    val loggerContext = new LoggerContext
    val configUrl = getClass.getClassLoader.getResource("actor-appender-spec.xml")
    val cf = new JoranConfigurator
    cf.setContext(loggerContext)
    cf.doConfigure(configUrl)
    loggerContext.start()
    StatusPrinter.printIfErrorsOccured(loggerContext)
    val logger = withStringListAppender.logger
    val latch = new StandardLatch()
    val actor = Actor.actorOf(new Actor {
      def receive = {
        case evt: ILoggingEvent if evt.getMessage == "The logged message" => latch.open()
        case _: ILoggingEvent => 
      }
    }).start()

    LogbackActor.addListener(actor)
    logger.info("The logged message")
    val res = latch.tryAwait(2, TimeUnit.SECONDS) must beTrue
    actor.stop()
    res
  }

  def logToChildAppenders = {
    val logger = withStringListAppender.logger
    val rootLogger = withStringListAppender.rootLogger
    val latch = new StandardLatch
    val actor = Actor.actorOf(new Actor {
      protected def receive = {
        case evt: ILoggingEvent if evt.getMessage == "the logged message" => latch.open()
        case _ =>
      }
    }).start()
    LogbackActor.addListener(actor)
    logger.info("the logged message")
    latch.tryAwait(2, TimeUnit.SECONDS) must beTrue
    val op = rootLogger.getAppender("ACTOR").asInstanceOf[ActorAppender[_]]
    val app = op.getAppender("STR_LIST").asInstanceOf[StringListAppender[_]]
    LogbackActor.removeListener(actor)
    actor.stop
    withStringListAppender.stopActor
    app.messages must contain("the logged message")
  }



  object withStringListAppender extends Before {
    val loggerContext = new LoggerContext
    val logger = loggerContext.getLogger(getClass)
    val rootLogger = loggerContext.getLogger("ROOT")
    var actor: ActorRef = _

    def before = {
      val latch = new StandardLatch
      actor = Actor.actorOf(new Actor {
        protected def receive = {
          case 'Start => latch.open()
          case _ =>
        }
      }).start()
      LogbackActor.addListener(actor)
      val configUrl = getClass.getClassLoader.getResource("actor-appender-spec.xml")
      val cf = new JoranConfigurator
      cf.setContext(loggerContext)
      cf.doConfigure(configUrl)
      loggerContext.start()
      StatusPrinter.printIfErrorsOccured(loggerContext)
//      StatusPrinter.print(loggerContext)
      latch.tryAwait(2, TimeUnit.SECONDS) // Block until the actors have been started
      Thread.sleep(500)
    }

    def stopActor = {
      LogbackActor.removeListener(actor)
      Option(actor) foreach { _.stop() }
    }
  }

}