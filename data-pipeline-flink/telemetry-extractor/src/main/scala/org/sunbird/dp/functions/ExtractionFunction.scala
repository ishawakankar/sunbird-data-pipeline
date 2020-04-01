package org.sunbird.dp.functions

import java.lang.reflect.Type
import java.util

import com.google.gson.Gson
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.util.Collector
import org.joda.time.format.DateTimeFormat
import org.sunbird.dp.task.ExtractionConfig
import org.sunbird.dp.domain._
import java.util.UUID

import com.google.gson.reflect.TypeToken

class ExtractionFunction(config: ExtractionConfig)(implicit val stringTypeInfo: TypeInformation[String])
  extends ProcessFunction[util.Map[String, AnyRef], String] {

  val mapType: Type = new TypeToken[util.Map[String, AnyRef]]() {}.getType

  /**
   * Method to process the events extraction from the batch
   *
   * @param batchEvent - Batch of telemetry events
   * @param context
   * @param collector
   */
  override def processElement(batchEvent: util.Map[String, AnyRef],
                              context: ProcessFunction[util.Map[String, AnyRef], String]#Context,
                              collector: Collector[String]): Unit = {

    val gson = new Gson()
    val eventsList = getEventsList(batchEvent)
    eventsList.forEach(event => {
      val syncts = batchEvent.get("syncts").asInstanceOf[Number].longValue()
      val eventData = updateEvent(event, syncts)
      val eventJson = gson.toJson(eventData)
      val eventSize = eventJson.getBytes("UTF-8").length
      if (eventSize > config.eventMaxSize) {
        context.output(config.failedEventsOutputTag, gson.toJson(markFailed(eventData)))
      } else {
        context.output(config.rawEventsOutputTag, gson.toJson(markSuccess(eventData)))
      }
    })

    /**
     * Generating Audit events to compute the number of events in the batch.
     */
    context.output(config.logEventsOutputTag,
      gson.fromJson(gson.toJson(generateAuditEvents(eventsList.size(), batchEvent)), mapType))
  }

  /**
   * Method to get the events from the batch.
   *
   * @param batchEvent - Batch of telemetry event.
   * @return Array[AnyRef] - List of telemetry events.
   */
  def getEventsList(batchEvent: util.Map[String, AnyRef]): util.ArrayList[util.Map[String, AnyRef]] = {
    Option(batchEvent.get("events")).getOrElse(new util.ArrayList[Any]).asInstanceOf[util.ArrayList[util.Map[String, AnyRef]]]
  }

  /**
   * Method to update the "SyncTS", "@TimeStamp" fileds of batch events into Events Object
   *
   * @param event  - Extracted Raw Telemetry Event
   * @param syncts - sync timestamp epoch to be updated in the events
   * @return - util.Map[String, AnyRef] Updated Telemetry Event
   */
  def updateEvent(event: util.Map[String, AnyRef], syncts: Long): util.Map[String, AnyRef] = {
    val timeStampString: String = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZoneUTC.print(syncts)
    event.put("syncts", Option(syncts.asInstanceOf[AnyRef]).getOrElse(System.currentTimeMillis().asInstanceOf[AnyRef]))
    event.put("@timestamp", timeStampString.asInstanceOf[AnyRef])
    event
  }

  /**
   * Method to Generate the LOG Event to Determine the Number of events has extracted.
   */
  def generateAuditEvents(totalEvents: Int, batchEvent: util.Map[String, AnyRef]): LogEvent = {
    LogEvent(
      actor = Actor("sunbird.telemetry", "telemetry-sync"),
      eid = "LOG",
      edata = EData(level = "INFO", "telemetry_audit", message = "telemetry sync", Array(Params("3.0", totalEvents, "SUCCESS"))),
      syncts = System.currentTimeMillis(),
      ets = System.currentTimeMillis(),
      context = org.sunbird.dp.domain.Context(channel = "in.sunbird", env = "data-pipeline",
        sid = UUID.randomUUID().toString,
        did = Option(getDeviceId(batchEvent, "did")).getOrElse(UUID.randomUUID()).toString,
        pdata = Pdata(ver = "3.0", pid = "telemetry-extractor"),
        cdata = null),
      mid = Option(batchEvent.get("mid")).getOrElse(UUID.randomUUID()).toString,
      `object` = Object(UUID.randomUUID().toString, "3.0", "telemetry-events", None),
      tags = null)
  }

  def markFailed(event: util.Map[String, AnyRef]): util.Map[String, AnyRef] = {
    val flags: util.HashMap[String, Boolean] = new util.HashMap[String, Boolean]()
    flags.put("ex_processed", false)
    val metaData: util.HashMap[String, AnyRef] = new util.HashMap[String, AnyRef]()
    metaData.put("src", config.jobName)
    metaData.put("ex_error", "Event size is Exceeded")
    event.asInstanceOf[util.Map[String, AnyRef]].put("metadata", metaData.asInstanceOf[util.Map[String, AnyRef]])
    event.asInstanceOf[util.Map[String, AnyRef]].put("flags", flags.asInstanceOf[util.Map[String, AnyRef]])
    event
  }

  def markSuccess(event: util.Map[String, AnyRef]): util.Map[String, AnyRef] = {
    val flags: util.HashMap[String, Boolean] = new util.HashMap[String, Boolean]()
    flags.put("ex_processed", true)
    event.put("flags", flags.asInstanceOf[util.Map[String, AnyRef]])
    event
  }

  def getDeviceId(batchEvents: util.Map[String, AnyRef], key: String): String = {
    val paramsObj = Option(batchEvents.get("params"))
    val messageId = paramsObj.map {
      params => params.asInstanceOf[util.Map[String, AnyRef]].get(key).asInstanceOf[String]
    }
    messageId.orNull
  }
}

