package com.openbank.dwh.persistence

import org.scalatest.concurrent.ScalaFutures
import com.openbank.dwh.utils.AkkaSpecBase
import org.scalatest.concurrent.IntegrationPatience
import java.nio.file.Paths
import akka.stream.scaladsl._
import akka.Done

class StreamSpec extends AkkaSpecBase("stream") with ScalaFutures with IntegrationPatience {

  it should "not throw null pointer exception on non existant path" in {
    val result = Source
      .single(Paths.get("neverthere"))
      .flatMapConcat { path =>
        val files = path.toFile.listFiles()
        if (files == null) {
          Source.empty
        } else {
          Source(files.map(_.getName).toIndexedSeq)
        }
      }
      .runWith(Sink.ignore)

    result.futureValue shouldBe Done
  }

}
