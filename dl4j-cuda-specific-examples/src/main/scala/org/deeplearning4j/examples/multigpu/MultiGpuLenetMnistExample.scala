package org.deeplearning4j.examples.multigpu

import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator
import org.deeplearning4j.eval.Evaluation
import org.deeplearning4j.nn.api.OptimizationAlgorithm
import org.deeplearning4j.nn.conf.inputs.InputType
import org.deeplearning4j.nn.conf.layers.{ConvolutionLayer, DenseLayer, OutputLayer, SubsamplingLayer}
import org.deeplearning4j.nn.conf.{NeuralNetConfiguration, Updater}
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.nn.weights.WeightInit
import org.deeplearning4j.optimize.listeners.ScoreIterationListener
import org.deeplearning4j.parallelism.ParallelWrapper
import org.nd4j.jita.conf.CudaEnvironment
import org.nd4j.linalg.activations.Activation
import org.nd4j.linalg.api.buffer.DataBuffer
import org.nd4j.linalg.api.buffer.util.DataTypeUtil
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.dataset.DataSet
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.lossfunctions.LossFunctions
import org.slf4j.{Logger, LoggerFactory}

/**
  * This is modified version of original LenetMnistExample, made compatible with multi-gpu environment
  *
  * @author @agibsonccc
  * @author raver119@gmail.com
  */
object MultiGpuLenetMnistExample {
  private val log: Logger = LoggerFactory.getLogger(MultiGpuLenetMnistExample.getClass)

  @throws[Exception]
  def main(args: Array[String]): Unit = {
    // PLEASE NOTE: For CUDA FP16 precision support is available
    DataTypeUtil.setDTypeForContext(DataBuffer.Type.HALF)

    // temp workaround for backend initialization
    Nd4j.create(1)

    CudaEnvironment.getInstance.getConfiguration
      // key option enabled
      .allowMultiGPU(true)

      // we're allowing larger memory caches
      .setMaximumDeviceCache(2L * 1024L * 1024L * 1024L)

      // cross-device access is used for faster model averaging over pcie
      .allowCrossDeviceAccess(true)

    val nChannels = 1
    val outputNum = 10

    // for GPU you usually want to have higher batchSize
    val batchSize = 128
    val nEpochs = 10
    val iterations = 1
    val seed = 123

    log.info("Load data....")
    val mnistTrain = new MnistDataSetIterator(batchSize, true, 12345)
    val mnistTest = new MnistDataSetIterator(batchSize, false, 12345)

    log.info("Build model....")
    val conf = new NeuralNetConfiguration.Builder()
      .seed(seed)
      .iterations(iterations) // Training iterations as above
      .regularization(true).l2(0.0005)
          /*
               Uncomment the following for learning decay and bias
          */
      .learningRate(.01)//.biasLearningRate(0.02)
      //.learningRateDecayPolicy(LearningRatePolicy.Inverse).lrPolicyDecayRate(0.001).lrPolicyPower(0.75)
      .weightInit(WeightInit.XAVIER)
      .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
      .updater(Updater.NESTEROVS).momentum(0.9)
      .list
      .layer(0, new ConvolutionLayer.Builder(5, 5)
        //nIn and nOut specify depth. nIn here is the nChannels and nOut is the number of filters to be applied
        .nIn(nChannels)
        .stride(1, 1)
        .nOut(20)
        .activation(Activation.IDENTITY)
        .build)
      .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
        .kernelSize(2, 2)
        .stride(2, 2)
        .build)
      .layer(2, new ConvolutionLayer.Builder(5, 5)
         //Note that nIn need not be specified in later layers
        .stride(1, 1)
        .nOut(50)
        .activation(Activation.IDENTITY)
        .build)
      .layer(3, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
        .kernelSize(2, 2)
        .stride(2, 2)
        .build)
      .layer(4, new DenseLayer.Builder()
        .activation(Activation.RELU)
        .nOut(500)
        .build)
      .layer(5, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
        .nOut(outputNum)
        .activation(Activation.SOFTMAX)
        .build)
      .setInputType(InputType.convolutionalFlat(28, 28, 1)) //See note below
      .backprop(true).pretrain(false).build
    val model = new MultiLayerNetwork(conf)
    model.init()

    // ParallelWrapper will take care of load balancing between GPUs.
    val wrapper = new ParallelWrapper.Builder(model)
      // DataSets prefetching options. Set this value with respect to number of actual devices
      .prefetchBuffer(24)

      // set number of workers equal or higher then number of available devices. x1-x2 are good values to start with
      .workers(4)

      // rare averaging improves performance, but might reduce model accuracy
      .averagingFrequency(3)

      // if set to TRUE, on every averaging model score will be reported
      .reportScoreAfterAveraging(true)

      // optinal parameter, set to false ONLY if your system has support P2P memory access across PCIe (hint: AWS do not support P2P)
      .useLegacyAveraging(true)

      .build

    log.info("Train model....")
    model.setListeners(new ScoreIterationListener(100))
    val timeX = System.currentTimeMillis

    // optionally you might want to use MultipleEpochsIterator instead of manually iterating/resetting over your iterator
    //MultipleEpochsIterator mnistMultiEpochIterator = new MultipleEpochsIterator(nEpochs, mnistTrain);
    for (i <- 0 until nEpochs) {
      val time1 = System.currentTimeMillis
      // Please note: we're feeding ParallelWrapper with iterator, not model directly
      //            wrapper.fit(mnistMultiEpochIterator);
      wrapper.fit(mnistTrain)
      val time2 = System.currentTimeMillis
      log.info("*** Completed epoch {}, time: {} ***", i, time2 - time1)
    }
    val timeY = System.currentTimeMillis

    log.info("*** Training complete, time: {} ***", timeY - timeX)

    log.info("Evaluate model....")

    val eval = new Evaluation(outputNum)
    while (mnistTest.hasNext) {
      val ds: DataSet = mnistTest.next
      val output: INDArray = model.output(ds.getFeatureMatrix, false)
      eval.eval(ds.getLabels, output)
    }
    log.info(eval.stats)
    mnistTest.reset()

    log.info("****************Example finished********************")
  }

}
