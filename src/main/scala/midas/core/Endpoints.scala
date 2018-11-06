// See LICENSE for license details.

package midas
package core

import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.config.Parameters

import chisel3._
import chisel3.util._
import chisel3.core.ActualDirection
import chisel3.core.DataMirror.directionOf
import widgets._
import junctions.{NastiIO, NastiKey, NastiParameters}
import scala.collection.mutable.{ArrayBuffer, HashSet}

trait Endpoint {
  protected val channels = ArrayBuffer[(String, Record)]()
  protected val wires = HashSet[Bits]()
  def matchType(data: Data): Boolean
  def widget(p: Parameters): EndpointWidget
  def widgetName: String = getClass.getSimpleName
  final def size = channels.size
  final def apply(wire: Bits) = wires(wire)
  final def apply(i: Int) = channels(i)
  def add(name: String, channel: Data) {
    val (ins, outs) = SimUtils.parsePorts(channel)
    wires ++= (ins ++ outs).unzip._1
    channels += (name -> channel.asInstanceOf[Record])
  }
}

abstract class SimMemIO extends Endpoint {
  // This is hideous, but we want some means to get the widths of the target
  // interconnect so that we can pass that information to the widget the
  // endpoint will instantiate.
  var targetAXI4Widths = collection.mutable.ArrayBuffer[NastiParameters]()
  private var initialized = false
  // Each channel may have a different memory model configuration. Index into the Field
  // based on how many models we've instantiated
  private var widgetIdx = 0

  override def add(name: String, channel: Data) {
    initialized = true
    super.add(name, channel)
    targetAXI4Widths += (channel match {
      case axi4: AXI4Bundle => NastiParameters(axi4.r.bits.data.getWidth,
                                               axi4.ar.bits.addr.getWidth,
                                               axi4.ar.bits.id.getWidth)
      case axi4: NastiIO => NastiParameters(axi4.r.bits.data.getWidth,
                                            axi4.ar.bits.addr.getWidth,
                                            axi4.ar.bits.id.getWidth)
      case _ => throw new RuntimeException("Unexpected channel type passed to SimMemIO")
    })
  }

  private def getChannelAXI4Parameters(idx: Int): NastiParameters = {
    scala.Predef.assert(initialized, "Widget instantiated without first binding a target channel.")
    targetAXI4Widths(idx)
  }

  def widget(p: Parameters) = {
    val axi4widths = getChannelAXI4Parameters(widgetIdx)
    val param = p alterPartial ({ case NastiKey => axi4widths })
    val gen = p(MemModelKey) match {
      case Nil => new NastiWidget()(param)
      case modelGen => modelGen(widgetIdx)(param)
    }
    widgetIdx += 1
    gen
  }
}

class SimNastiMemIO extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: NastiIO =>
      directionOf(channel.w.valid) == ActualDirection.Output
    case _ => false
  }
}

class SimAXI4MemIO extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: AXI4Bundle =>
      directionOf(channel.w.valid) == ActualDirection.Output
    case _ => false
  }
}

case class EndpointMap(endpoints: Seq[Endpoint]) {
  def get(data: Data) = endpoints find (_ matchType data)
  def ++(x: EndpointMap) = EndpointMap(endpoints ++ x.endpoints) 
}
