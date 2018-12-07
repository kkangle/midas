// See LICENSE for license details.

package midas
package widgets

import scala.collection.immutable.ListMap

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}


import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}

import junctions._

import midas.{PrintPorts}
import midas.core.{HostPort, DMANastiKey}

class PrintRecord(argTypes: Seq[firrtl.ir.Type]) extends Record {
  def regenLeafType(tpe: firrtl.ir.Type): Data = tpe match {
    case firrtl.ir.UIntType(width: firrtl.ir.IntWidth) => UInt(width.width.toInt.W)
    case firrtl.ir.SIntType(width: firrtl.ir.IntWidth) => SInt(width.width.toInt.W)
    case badType => throw new RuntimeException(s"Unexpected type in PrintBundle: ${badType}")
  }
  val enable = Output(Bool())
  val args: Seq[(String, Data)] =argTypes.zipWithIndex.map({ case (tpe, idx) =>
    "args_${idx}" -> Output(regenLeafType(tpe))
  })
  val elements = ListMap((Seq("enable" -> enable) ++ args):_*)
  override def cloneType = new PrintRecord(argTypes).asInstanceOf[this.type]
}


class PrintRecordBag(prefix: String, printPorts: Seq[firrtl.ir.Port]) extends Record {
  val ports: Seq[(String, PrintRecord)] = printPorts.collect({ 
      case firrtl.ir.Port(_, name, _, firrtl.ir.BundleType(fields)) =>
    val argTypes = fields.flatMap({_  match {
      case firrtl.ir.Field(name, _, _) if name == "enable" => None
      case firrtl.ir.Field(_, _, tpe) => Some(tpe)
    }})
    name.stripPrefix(prefix) -> new PrintRecord(argTypes)
  })
  val elements = ListMap(ports:_*)
  override def cloneType = new PrintRecordBag(prefix, printPorts).asInstanceOf[this.type]
}

class PrintRecordEndpoint extends Endpoint {
  var initialized = false
  var printRecordGen: PrintRecordBag = new PrintRecordBag("dummy", Seq())

  def matchType(data: Data) = data match {
    case channel: PrintRecordBag =>
      require(DataMirror.directionOf(channel) == Direction.Output, "PrintRecord has unexpected direction")
      initialized = true
      printRecordGen = channel.cloneType
      true
    case _ => false
  }
  def widget(p: Parameters) = {
    require(initialized, "Attempted to generate an PrintWidget before inspecting input data bundle")
    new PrintWidget(printRecordGen)(p)
  }
  override def widgetName = "PrintWidget"
}

class PrintWidgetIO(private val record: PrintRecordBag)(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(record))
}

class PrintWidget(printRecord: PrintRecord)(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new PrintWidgetIO(printRecord))
  io <> DontCare

//  val printPort = io.hPort.hBits
//  val fire = Wire(Bool())
//  val cycles = Reg(UInt(48.W))
//  val enable = RegInit(false.B)
//  val enableAddr = attach(enable, "enable")
//  val printAddrs = collection.mutable.ArrayBuffer[Int]()
//  val countAddrs = collection.mutable.ArrayBuffer[Int]()
//  val channelWidth = io.dma.get.nastiXDataBits
//  val printWidth = (printPort.elements foldLeft 56)(_ + _._2.getWidth - 1)
//  val valid = (printPort.elements foldLeft false.B)( _ || _._2(0))
//  val ps = printPort.elements.toSeq map (_._2 >> 1)
//  val vs = printPort.elements.toSeq map (_._2(0))
//  val data = Cat(Cat(ps.reverse), Cat(vs.reverse) | 0.U(8.W), cycles)
//  /* FIXME */ scala.Predef.assert(printWidth <= channelWidth)
//
//  val prints = (0 until printWidth by channelWidth).zipWithIndex map { case (off, i) =>
//    val width = channelWidth min (printWidth - off)
//    val wires = Wire(Decoupled(UInt(width.W)))
//    val queue = BRAMQueue(wires, 8 * 1024)
//    wires.bits  := data(width + off - 1, off)
//    wires.valid := fire && enable && valid
//    if (countAddrs.isEmpty) {
//      val count = RegInit(0.U(24.W))
//      count suggestName "count"
//      when (wires.fire() === queue.fire()) {
//      }.elsewhen(wires.fire()) {
//        count := count + 1.U
//      }.elsewhen(queue.fire()) {
//        count := count - 1.U
//      }
//      countAddrs += attach(count, "prints_count", ReadOnly)
//    }
//
//    io.dma.foreach({ dma =>
//      val arQueue   = Queue(dma.ar, 10)
//      val readBeats = RegInit(0.U(9.W))
//      readBeats suggestName "readBeats"
//      when(dma.r.fire()) {
//        readBeats := Mux(dma.r.bits.last, 0.U, readBeats + 1.U)
//      }
//
//      queue.ready := dma.r.ready && arQueue.valid
//      dma.r.valid := queue.valid && arQueue.valid
//      dma.r.bits.data := queue.bits
//      dma.r.bits.last := arQueue.bits.len === readBeats
//      dma.r.bits.id   := arQueue.bits.id
//      dma.r.bits.user := arQueue.bits.user
//      dma.r.bits.resp := 0.U
//      arQueue.ready := dma.r.fire() && dma.r.bits.last
//
//      // No write
//      dma.aw.ready := false.B
//      dma.w.ready := false.B
//      dma.b.valid := false.B
//      dma.b.bits := DontCare
//    })
//    wires.ready || !valid
//  }
//  fire := (prints foldLeft (io.hPort.toHost.hValid && io.tReset.valid))(_ && _)
//  io.tReset.ready := fire
//  io.hPort.toHost.hReady := fire
//  // We don't generate tokens
//  io.hPort.fromHost.hValid := true.B
//  when (fire) {
//    cycles := Mux(io.tReset.bits, 0.U, cycles + 1.U)
//  }
//
//  override def genHeader(base: BigInt, sb: StringBuilder) {
//    import CppGenerationUtils._
//    sb.append(genComment("Print Widget"))
//    sb.append(genMacro("PRINTS_NUM", UInt32(printPort.elements.size)))
//    sb.append(genMacro("PRINTS_CHUNKS", UInt32(prints.size)))
//    sb.append(genMacro("PRINTS_ENABLE", UInt32(base + enableAddr)))
//    sb.append(genMacro("PRINTS_COUNT_ADDR", UInt32(base + countAddrs.head)))
//    sb.append(genArray("PRINTS_WIDTHS", printPort.elements.toSeq map (x => UInt32(x._2.getWidth))))
//    if (!p(HasDMAChannel)) {
//      sb.append(genArray("PRINTS_DATA_ADDRS", printAddrs.toSeq map (x => UInt32(base + x))))
//    } else {
//      sb.append(genMacro("HAS_DMA_CHANNEL"))
//    }
//  }
//
//  genCRFile()
}
