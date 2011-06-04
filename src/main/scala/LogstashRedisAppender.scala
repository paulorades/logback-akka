package com.mojolly.logback

import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.{Jedis, JedisPool}
import scala.reflect.BeanProperty
import java.util.Locale
import scala.util.matching.Regex
import collection.JavaConversions._
import collection.mutable
import collection.JavaConverters._
import ch.qos.logback.classic.spi.{ILoggingEvent}
import ch.qos.logback.core.{LayoutBase, UnsynchronizedAppenderBase}
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._
import net.liftweb.json.{DefaultFormats, Extraction, Printer, JsonParser}

object LogstashRedisLayout {
  implicit var formats = DefaultFormats
}
class LogstashRedisLayout[E] extends LayoutBase[E] {
  import com.mojolly.logback.LogstashRedisLayout._
  private val TAG_REGEX: Regex = """(?iu)\B#([^,#=!\s./]+)([\s,.]|$)""".r

  @BeanProperty var applicationName: String = ""

  def doLayout(p1: E) = {
    val event = p1.asInstanceOf[ILoggingEvent]
    val msg = event.getFormattedMessage
    val tags = parseTags(msg)
    val jv: JValue =
      ("@timestamp" -> event.getTimeStamp) ~
      ("@tags" -> tags) ~
      ("@type" -> "string") ~
      ("@source" -> event.getLoggerName) ~
      ("@message" -> event.getFormattedMessage)

    val fields = {
      exceptionFields(event) merge {
        val mdc = { if (event.getMdc == null) JNothing else Extraction.decompose(event.getMdc.asScala) }
        (mdc merge
          ("thread_name" -> event.getThreadName) ~
          ("level" -> event.getLevel.toString) ~
          ("application" -> applicationName))
      }
    }

    Printer.compact {
      render {
        jv merge ("@fields" -> fields)
      }
    }
  }

  private def exceptionFields(event: ILoggingEvent): JValue = {
    if (event.getThrowableProxy == null) {
      JNothing
    } else {
      val th = event.getThrowableProxy
      val stea: Seq[StackTraceElement] = if(th.getStackTraceElementProxyArray != null) {
        th.getStackTraceElementProxyArray.map(_.getStackTraceElement)
      } else {
        List.empty[StackTraceElement]
      }
      ("error_message" -> th.getMessage) ~
      ("error" -> th.getClassName) ~
      ("stack_trace" -> (stea map { stl =>
        val jv: JValue =
          ( "line" -> stl.getLineNumber ) ~
          ( "file" -> stl.getFileName ) ~
          ( "method_name" -> stl.getMethodName)
        jv
      }))
    }
  }

  private def parseTags(msg: String) = {
    TAG_REGEX.findAllIn(msg).matchData.map(_.group(1).toUpperCase(Locale.ENGLISH)).toSet
  }

}
class LogstashRedisAppender[E] extends UnsynchronizedAppenderBase[E] {

  @BeanProperty var host = "localhost"
  @BeanProperty var port = 6379
  @BeanProperty var database = 9
  @BeanProperty var queueName: String = _

  private var redisPool: JedisPool = _


  override def start() {
    super.start()
    redisPool = createPool
  }


  override def stop() {
    if(started) {
      try { Option(redisPool) foreach { _.destroy() } } catch { case _ => } // if you die do it quietly
    }
    super.stop()
  }

  def append(p1: E) {
    val layout = new LogstashRedisLayout[E]
    withRedis { _.rpush(queueName, layout.doLayout(p1)) }
  }

  def returnClient(client: Jedis) {
    try {
      redisPool.returnResource(client)
    } catch {
      case e: JedisException => {
        if(redisPool != null) { redisPool.destroy() }
        redisPool = createPool
      }
    }
  }

  def returnBrokenResource(client: Jedis) {
    try {
      redisPool.returnBrokenResource(client)
    } catch {
      case e: JedisException => {
        if(redisPool != null) { redisPool.destroy() }
        redisPool = createPool
      }
    }
  }

  private def createPool = new JedisPool(host, port)

  def createClient = try {
    val cl = redisPool.getResource
    if(!cl.isConnected) cl.connect()
    cl select database
    cl
  } catch {
    case e: JedisException => {
      if(redisPool != null) { redisPool.destroy() }
      redisPool = createPool
      val sec = redisPool.getResource
      sec select database
      sec
    }
  }

  def withRedis[T](block: Jedis => T): T = {
    val client = createClient
    try {
      val res = block(client)
      returnClient(client)
      res
    } catch {
      case e: JedisException => {
        addInfo("Redis was disconnected, reconnecting...")
        withRedis(block)
      }
      case e => {
        addError("There was a problem using jedis", e)
        throw e
      }
    }
  }
}