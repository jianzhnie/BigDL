/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.parameters

import java.nio.ByteBuffer
import java.util.concurrent.atomic.{AtomicInteger}
import java.util.concurrent.{Callable, Executors, ThreadFactory}

import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.utils.{Engine, T, Table}
import org.apache.commons.lang.exception.ExceptionUtils
import org.apache.log4j.Logger

import org.apache.spark.sparkExtension.{ParameterManagerMaster, SparkExtension}
import org.apache.spark.{SparkEnv}
import org.apache.spark.storage.{BlockId, BlockManagerWrapper, StorageLevel}

import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap
import scala.reflect._

object ParameterManager2 {
  val logger = Logger.getLogger(getClass)

  private val nextId = new AtomicInteger(0)

  private val pm = new HashMap[Int, ParameterManager2]()

  private var executorIdMap: HashMap[String, Int] = null

  def setExecutorMap(map: HashMap[String, Int]): Unit = {
    executorIdMap = map
  }

  def get(executorId: String): ParameterManager2 = {
    val id = executorIdMap(executorId)
    if (pm.contains(id)) pm(id)
    else null
  }

  def createParameterManager[T: ClassTag](executorId: Int, executorNum: Int, partitionNum: Int,
    size: Int, port: Int = -1): ParameterManager2 = {
    val id = nextId.getAndIncrement()
    val conf = SparkEnv.get.conf
    val master = ParameterManagerMaster.createEnv(conf, port)
    val p = new ParameterManager2(id, executorId, executorNum, partitionNum, size, master)
    pm.put(executorId, p)
    p
  }
}

/**
  * Represent a parameter stored on block manager. In distributed optimization, we put parameter
  * on block manager of spark. Each worker sync parameter through block manager. Block manager
  * here serve as a parameter server.
  * @param id distinguish from other parameters
  * @param executorId executor id which hosted the parameter
  * @param partitionNum how many partitions will use this parameter
  * @param size size of the parameter(1D vector)
  * @param master master used to communicate betwwen driver and executor
  */
class ParameterManager2(val id: Int, val executorId: Int,
  executorNum: Int, partitionNum: Int, size: Int, val master: ParameterManagerMaster) {
  import ParameterManager2._

  private val syncPoolSize: Int = System.getProperty(
    "bigdl.Parameter.syncPoolSize", "4").toInt

  val syncPool = Executors.newFixedThreadPool(syncPoolSize, new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val t = Executors.defaultThreadFactory().newThread(r)
      t.setDaemon(true)
      t
    }
  })

  var job1Start: Boolean = false
  var initFinished = false

  val taskSize = size / executorNum
  val extraSize = size % executorNum

  /** Set initialize parameter */
  def init[T: ClassTag](parameter: Tensor[T], state: Table)
    (implicit ev: TensorNumeric[T]): Unit = {
    val _classTag = classTag[T]
    val start = executorId * taskSize + math.min(executorId, extraSize)
    val length = taskSize + (if (executorId < extraSize) 1 else 0)
    val _weightsExecutor = Tensor[T](length)(_classTag, ev).copy(parameter.narrow(1,
      start + 1, length))
    BlockManagerWrapper.putSingle(getWeightExecutorId(),
      _weightsExecutor, StorageLevel.MEMORY_AND_DISK, tellMaster = false)

    BlockManagerWrapper.putSingle(getWeightId(),
      parameter, StorageLevel.MEMORY_AND_DISK, tellMaster = false)

    val _gradientsExecutor = Tensor[T](length)(_classTag, ev)
    BlockManagerWrapper.putSingle(getGradientExecutorId(),
      _gradientsExecutor, StorageLevel.MEMORY_AND_DISK, tellMaster = false)

    val blockId = getWeightBlockId(executorId)
    val fp16param = new FP16CompressedTensor[T](length)(_classTag)
    fp16param.compress(0, parameter, start, length)
    BlockManagerWrapper.putBytes(blockId, fp16param.bytes(), StorageLevel.MEMORY_ONLY_SER)
  }

  /** Aggregate gradients hosted in one executor */
  def aggregateLocalGradient[T: ClassTag]() : Tensor[T] = {
    val blockIds = master.getBlockId(executorId)
    val gradientBuffer = new Array[Tensor[T]](blockIds.size)
    Engine.default.invokeAndWait((0 until blockIds.size).map(tid => () => {
      gradientBuffer(tid) = getLocalParameter(blockIds(tid))
    }))

    val poolSize = Engine.default.getPoolSize
    val innerTaskSize = size / poolSize
    val innerExtraSize = size % poolSize
    val availableTask = if (innerTaskSize == 0) innerExtraSize else poolSize

    Engine.default.invokeAndWait((0 until availableTask).map(tid => () => {
      val innerStart = tid * innerTaskSize + math.min(tid, innerExtraSize)
      val innerLength = innerTaskSize + (if (tid < innerExtraSize) 1 else 0)
      var i = 1
      while (i < gradientBuffer.length) {
        gradientBuffer(0).narrow(1, innerStart + 1, innerLength)
          .add(gradientBuffer(i).narrow(1, innerStart + 1, innerLength))
        i += 1
      }
    }))
    master.clearBlockId(executorId)
    gradientBuffer(0)
  }

  /** Split aggregated gradient into executor number and put them in blockmanager */
  def putGradients[T: ClassTag](parameter: Tensor[T]): Unit = {
    val _classTag = classTag[T]
    var pid = 0
    while (pid < executorNum) {
      val start = pid * taskSize + math.min(pid, extraSize)
      val length = taskSize + (if (pid < extraSize) 1 else 0)
      val blockId = getGradientBlockId(executorId, pid)
      val fp16param = new FP16CompressedTensor[T](length)(_classTag)
      fp16param.compress(0, parameter, start, length)
//      BlockManagerWrapper.putBytes(blockId, fp16param.bytes(), StorageLevel.MEMORY_ONLY_SER)
      val block = BlockManagerWrapper.getLocalBytes(blockId)
      if (block.isDefined) {
        block.get.put(fp16param.bytes())
      } else {
        val bytes = ByteBuffer.allocate(fp16param.bytes().limit)
        bytes.put(fp16param.bytes())
        BlockManagerWrapper.putBytes(blockId, bytes, StorageLevel.MEMORY_ONLY_SER)
      }

      pid += 1
    }
  }

  /** Fetch partital gradients from local or remote nodes, aggregate them */
  def aggregrateGradientParition[T: ClassTag](): Unit = {
    val params = new Array[CompressedTensor[T]](executorNum)
    val sgThreads = (0 until executorNum).map(pid => {
      new Callable[Int] {
        override def call(): Int = {
          try {
            val blockId = getGradientBlockId(pid, executorId)
            val tmp = BlockManagerWrapper.getLocalOrRemoteBytes(blockId).get
            params(pid) = SerializerInstance.serialize(tmp)
            BlockManagerWrapper.unlock(blockId)
            pid
          } catch {
            case t : Throwable =>
              logger.error("Error: " + ExceptionUtils.getStackTrace(t))
              throw t
          }
        }
      }
    })
    syncPool.invokeAll(sgThreads.asJava)

    val length = taskSize + (if (executorId < extraSize) 1 else 0)
    val poolSize = Engine.default.getPoolSize
    val innerTaskSize = length / poolSize
    val innerExtraSize = length % poolSize
    val availableTask = if (innerTaskSize == 0) innerExtraSize else poolSize

    Engine.default.invokeAndWait((0 until availableTask).map(tid => () => {
      val innerStart = tid * innerTaskSize + math.min(innerExtraSize, tid)
      val innerLength = innerTaskSize + (if (tid < innerExtraSize) 1 else 0)
      params.reduce((l, r) => l.add(r.bytes(innerStart, innerLength), innerStart,
        innerLength))
        }))
    val gradientExecutorId = getGradientExecutorId()
    val gradientExecutor = getLocalParameter[T](gradientExecutorId)
    params.head.deCompress(gradientExecutor)
  }

  /** Fetch partial weights from remote nodes and concat them as a complete weight */
  def syncWeights[T: ClassTag](localParameter: Tensor[T]): Unit = {
    val bm = SparkEnv.get.blockManager
    val tasks = (0 until executorNum).map(pid => {
      new Callable[Int] {
        override def call(): Int = {
          try {
            val blockId = getWeightBlockId(pid)
            val localBuffer = BlockManagerWrapper.getLocalOrRemoteBytes(blockId).getOrElse {
              throw new RuntimeException(s"Didn't find weight block $blockId in the block " +
                s"manager. Did you initialize this AllReduceParameter on every executor?")
            }
            val start = pid * taskSize + math.min(pid, extraSize)
            val length = taskSize + (if (pid < extraSize) 1 else 0)
            require(localBuffer.array().length == length * 2)
            SerializerInstance.serialize[T](localBuffer)
              .deCompress(0, localParameter, start, length)
            BlockManagerWrapper.unlock(blockId)
            pid
          } catch {
            case t : Throwable =>
              logger.error("Error: " + ExceptionUtils.getStackTrace(t))
              throw t
          }
        }
      }
    })
    syncPool.invokeAll(tasks.asJava)
  }

  /** Put the partial weight in the blockmanager */
  def sendWeightExecutor[T: ClassTag]() : Unit = {
    val weightExecutorId = getWeightExecutorId()
    val weightExecutor = getLocalParameter(weightExecutorId)
    val blockId = getWeightBlockId(executorId)
    BlockManagerWrapper.removeBlock(blockId)
    val data = SerializerInstance.serialize(weightExecutor)
    val block = BlockManagerWrapper.getLocalBytes(blockId)
    if (block.isDefined) {
      block.get.put(data.bytes())
    } else {
      val bytes = ByteBuffer.allocate(data.bytes().limit)
      bytes.put(data.bytes())
      BlockManagerWrapper.putBytes(blockId, bytes, StorageLevel.MEMORY_ONLY_SER)
    }
//    BlockManagerWrapper.putBytes(blockId,
//      SerializerInstance.serialize(weightExecutor).bytes(), StorageLevel.MEMORY_ONLY_SER)
  }

  /** Get a block from local blockmanager */
  def getLocalParameter[T: ClassTag](blockId: BlockId): Tensor[T] = {
    BlockManagerWrapper.getLocal(blockId)
      .map(_.data.next().asInstanceOf[Tensor[T]])
      .getOrElse(throw new IllegalStateException("Please initialize AllReduceParameter first!"))
  }

  /** Put a gradient in local blockmanager */
  def sendGradients[T: ClassTag](gradient: Tensor[T], pid: Int): Unit = {
    val gradientsId = getGradientPartitionId(pid)

    val gradients = BlockManagerWrapper.getLocal(gradientsId)
      .map(_.data.next().asInstanceOf[Tensor[T]])
      if (gradients.isDefined) {
        gradients.get.copy(gradient)
      } else {
        BlockManagerWrapper.putSingle(gradientsId, gradient, StorageLevel.MEMORY_AND_DISK,
          tellMaster = false)
      }
    master.updateBlockId(executorId, gradientsId)
  }

  def getGradientBlockId(pidFrom : Int, pidTo : Int): BlockId = {
    SparkExtension.getLocalBlockId("pm" + pidTo + "gBytes" + pidFrom)
  }

  def getWeightBlockId(pid : Int): BlockId = {
    SparkExtension.getLocalBlockId("pm_wBytes" + pid)
  }

  def getWeightExecutorId(): BlockId = {
    SparkExtension.getLocalBlockId("pm_w" + executorId)
  }

  def getWeightId(): BlockId = {
    SparkExtension.getLocalBlockId("weight" + executorId)
  }

  def getGradientPartitionId(pid: Int): BlockId = {
    SparkExtension.getLocalBlockId("pm_g_p" + pid)
  }

  def getGradientExecutorId(): BlockId = {
    SparkExtension.getLocalBlockId("pm_g" + executorId)
  }
}