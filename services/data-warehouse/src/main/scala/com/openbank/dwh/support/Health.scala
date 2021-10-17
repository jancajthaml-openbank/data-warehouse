package com.openbank.dwh.support

import com.typesafe.scalalogging.StrictLogging
import java.nio.file.{Files, LinkOption, Path}
import com.sun.jna.{Library, Native}

case object Health extends StrictLogging {

  private trait SystemD extends Library {
    def sd_notify(unset_environment: Int, state: String): Int
  }

  private lazy val SYSTEMD: SystemD = {
    val address = System.getenv.get("NOTIFY_SOCKET")
    if (address == null || address.isEmpty) {
      null
    } else if (
      !Files.isDirectory(
        Path.of("/run/systemd/system"),
        LinkOption.NOFOLLOW_LINKS
      )
    ) {
      null
    } else {
      Native.load("systemd", classOf[SystemD])
    }
  }

  private def systemNotify(msg: String): Unit = {
    if (SYSTEMD == null) {
      return
    }
    try {
      SYSTEMD.sd_notify(0, msg)
    } catch {
      case e: Exception =>
        logger.warn("System Notify failed", e)
    }
  }

  def serviceReady(): Unit = {
    systemNotify("READY=1")
  }

  def serviceStopping(): Unit = {
    systemNotify("STOPPING=1")
  }

}
