/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.sparkdl.dataset

import java.awt.Color
import java.awt.image.{BufferedImage, DataBufferByte}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File, FileInputStream}
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Path
import javax.imageio.ImageIO

abstract class Image(protected var data: Array[Float], protected var _width: Int,
  protected var _height: Int, protected var _label: Float) extends Serializable {

  def width(): Int = _width

  def height(): Int = _height

  def content: Array[Float] = data

  def label(): Float = _label

  def setLabel(label: Float): this.type = {
    this._label = label
    this
  }
}

class GreyImage(d: Array[Float], w: Int, h: Int, l: Float) extends Image(d, w, h, l) {
  def this(_width: Int, _height: Int) =
    this(new Array[Float](_width * _height), _width, _height, 0.0f)

  def this() = this(new Array[Float](0), 0, 0, 0)

  def copy(source: Array[Byte], scale: Float = 1.0f, offset: Int = 0): this.type = {
    require(data.length + offset <= source.length)
    var i = 0
    while (i < data.length) {
      data(i) = (source(i + offset) & 0xff) / scale
      i += 1
    }
    this
  }

  def copy(other: GreyImage): GreyImage = {
    this._width = other._width
    this._height = other._height
    this._label = other.label
    if (this.data.length < this._width * this._height) {
      this.data = new Array[Float](this._width * this._height)
    }

    var i = 0
    while (i < this._width * this._height) {
      this.data(i) = other.data(i)
      i += 1
    }
    this
  }
}

class RGBImage(d: Array[Float], w: Int, h: Int, l: Float) extends Image(d, w, h, l) {
  def this() = this(new Array[Float](0), 0, 0, 0)

  def this(_width: Int, _height: Int) =
    this(new Array[Float](_width * _height * 3), _width, _height, 0.0f)

  def copy(rawData: Array[Byte], scale: Float = 255.0f): this.type = {
    val buffer = ByteBuffer.wrap(rawData)
    _width = buffer.getInt
    _height = buffer.getInt
    require(rawData.length == 8 + _width * _height * 3)
    if (data.length < _height * _width * 3) {
      data = new Array[Float](_width * _height * 3)
    }
    var i = 0
    while (i < _width * _height * 3) {
      data(i) = (rawData(i + 8) & 0xff) / scale
      i += 1
    }
    this
  }

  def copyTo(storage: Array[Float], offset: Int) : Unit = {
    val frameLength = width() * height()
    require(frameLength * 3 + offset <= storage.length)
    var j = 0
    while (j < frameLength) {
      storage(offset + j) = content(j * 3)
      storage(offset + j + frameLength) = content(j * 3 + 1)
      storage(offset + j + frameLength * 2) = content(j * 3 + 2)
      j += 1
    }
  }

  def save(path: String, scale: Float = 255.0f): Unit = {
    val image = new BufferedImage(width(), height(), BufferedImage.TYPE_INT_BGR)
    var y = 0
    while (y < height()) {
      var x = 0
      while (x < width()) {
        val r = (data((x + y * width()) * 3 + 2) * scale).toInt
        val g = (data((x + y * width()) * 3 + 1) * scale).toInt
        val b = (data((x + y * width()) * 3) * scale).toInt
        image.setRGB(x, y, (r << 16) | (g << 8) | b)
        x += 1
      }
      y += 1
    }

    ImageIO.write(image, "jpg", new File(path))
  }

  def copy(other: RGBImage): RGBImage = {
    this._width = other._width
    this._height = other._height
    this._label = other._label
    if (this.data.length < this._width * this._height * 3) {
      this.data = new Array[Float](this._width * this._height * 3)
    }

    var i = 0
    while (i < this._width * this._height * 3) {
      this.data(i) = other.data(i)
      i += 1
    }
    this
  }
}

object RGBImage {
  def readImage(path: Path, scaleTo: Int): Array[Byte] = {
    var fis : FileInputStream = null
    try {
      fis = new FileInputStream(path.toString)
      val channel = fis.getChannel
      val byteArrayOutputStream = new ByteArrayOutputStream
      channel.transferTo(0, channel.size, Channels.newChannel(byteArrayOutputStream))
      val img = ImageIO.read(new ByteArrayInputStream(byteArrayOutputStream.toByteArray))
      var heightAfterScale = 0
      var widthAfterScale = 0
      var scaledImage: java.awt.Image = null
      // no scale
      if (-1 == scaleTo) {
        heightAfterScale = img.getHeight
        widthAfterScale = img.getWidth
        scaledImage = img
      } else {
        if (img.getWidth < img.getHeight) {
          heightAfterScale = scaleTo * img.getHeight / img.getWidth
          widthAfterScale = scaleTo
        } else {
          heightAfterScale = scaleTo
          widthAfterScale = scaleTo * img.getWidth / img.getHeight
        }
        scaledImage =
          img.getScaledInstance(widthAfterScale, heightAfterScale, java.awt.Image.SCALE_SMOOTH)
      }

      val imageBuff: BufferedImage =
        new BufferedImage(widthAfterScale, heightAfterScale, BufferedImage.TYPE_3BYTE_BGR)
      imageBuff.getGraphics.drawImage(scaledImage, 0, 0, new Color(0, 0, 0), null)
      val pixels: Array[Byte] =
        (imageBuff.getRaster.getDataBuffer.asInstanceOf[DataBufferByte]).getData
      require(pixels.length % 3 == 0)

      val bytes = new Array[Byte](8 + pixels.length)
      val byteBuffer = ByteBuffer.wrap(bytes)
      require(imageBuff.getWidth * imageBuff.getHeight * 3 == pixels.length)
      byteBuffer.putInt(imageBuff.getWidth)
      byteBuffer.putInt(imageBuff.getHeight)
      System.arraycopy(pixels, 0, bytes, 8, pixels.length)
      bytes
    } catch {
      case ex: Exception =>
        ex.printStackTrace
        System.err.println("Can't read file " + path)
        throw ex
    } finally {
      if (fis != null) {
        fis.close()
      }
    }
  }

  def convertToByte(data : Array[Float], length : Int, width : Int, scaleTo: Float = 255.0f):
  Array[Byte] = {
    var i = 0
    val res = new Array[Byte](length * width * 3)
    while(i < length * width * 3) {
      res(i) = (data(i) * scaleTo).toByte
      i += 1
    }
    res
  }
}