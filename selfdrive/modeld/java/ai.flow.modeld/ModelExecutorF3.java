package ai.flow.modeld;

import ai.flow.common.ParamsInterface;
import ai.flow.common.transformations.Camera;
import ai.flow.common.utils;
import ai.flow.definitions.Definitions;
import ai.flow.modeld.messages.MsgFrameData;
import ai.flow.modeld.messages.MsgModelRaw;
import ai.flow.sensor.messages.MsgFrameBuffer;
import messaging.ZMQPubHandler;
import messaging.ZMQSubHandler;
import org.capnproto.PrimitiveList;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.AllocationPolicy;
import org.nd4j.linalg.api.memory.enums.LearningPolicy;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.opencv.core.Core;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static ai.flow.common.SystemUtils.getUseGPU;
import static ai.flow.common.utils.numElements;
import static ai.flow.sensor.messages.MsgFrameBuffer.updateImageBuffer;

public class ModelExecutorF3 extends ModelExecutor implements Runnable{

    public boolean stopped = true;
    boolean exit = false;
    public Thread thread;
    public final String threadName = "modeld";
    public boolean initialized = false;
    public long timePerIt = 0;
    public static long AvgIterationTime = 0;
    public long iterationNum = 1;
    public static long cameraImageTimestamp;

    public static int[] imgTensorShape = {1, 12, 128, 256};
    public static final int[] desireTensorShape = {1, CommonModelF3.DESIRE_LEN, CommonModelF3.HISTORY_BUFFER_LEN+1};
    public static final int[] trafficTensorShape = {1, CommonModelF3.TRAFFIC_CONVENTION_LEN};
    public static final int[] featureTensorShape = {1, CommonModelF3.FEATURE_LEN, CommonModelF3.HISTORY_BUFFER_LEN};
    public static final int[] outputTensorShape = {1, CommonModelF3.NET_OUTPUT_SIZE};
    public static final int[] navFeaturesTensorShape = {1, CommonModelF3.NAV_FEATURE_LEN};
    public static final int[] navInstructionsTensorShape = {1, 150};

    public static final Map<String, int[]> inputShapeMap = new HashMap<>();
    public static final Map<String, int[]> outputShapeMap = new HashMap<>();
    public final INDArray desireNDArr = Nd4j.zeros(desireTensorShape);
    public final INDArray trafficNDArr = Nd4j.zeros(trafficTensorShape);
    public final INDArray featuresNDArr = Nd4j.zeros(featureTensorShape);
    public final float[] netOutputs = new float[(int)numElements(outputTensorShape)];
    public final INDArray augmentRot = Nd4j.zeros(3);
    public final INDArray augmentTrans = Nd4j.zeros(3);
    public final float[]prevDesire = new float[CommonModelF3.DESIRE_LEN];
    public final float[]desireIn = new float[CommonModelF3.DESIRE_LEN];
    public final Map<String, INDArray> inputMap =  new HashMap<>();
    public final Map<String, float[]> outputMap =  new HashMap<>();

    public final ParamsInterface params = ParamsInterface.getInstance();

    public static final int[] FULL_FRAME_SIZE = Camera.frameSize;
    public static INDArray road_intrinsics = Camera.road_intrinsics.dup(); // telephoto
    public static INDArray wide_intrinsics = Camera.wide_intrinsics.dup(); // wide
    public final ZMQPubHandler ph = new ZMQPubHandler();
    public final ZMQSubHandler sh = new ZMQSubHandler(true);
    public MsgModelRaw msgModelRaw = new MsgModelRaw();
    public Definitions.LiveCalibrationData.Reader liveCalib;

    public long start, end;
    public int lastFrameID = -1;
    public int firstFrameID = -1;
    public int totalFrameDrops = 0;
    public int frameDrops = 0;

    int desire;
    public ModelRunner modelRunner;
    public static volatile boolean NeedImage;
    public static Definitions.FrameData.Reader frameData;
    public static Definitions.FrameData.Reader frameWideData;
    public static Definitions.FrameBuffer.Reader msgFrameBuffer;
    public static Definitions.FrameBuffer.Reader msgFrameWideBuffer;
    ByteBuffer imgBuffer;
    ByteBuffer wideImgBuffer;
    boolean snpe;
    final WorkspaceConfiguration wsConfig = WorkspaceConfiguration.builder()
            .policyAllocation(AllocationPolicy.STRICT)
            .policyLearning(LearningPolicy.FIRST_LOOP)
            .build();

    public ModelExecutorF3(ModelRunner modelRunner){
        this.modelRunner = modelRunner;
    }

    public boolean isIntrinsicsValid(PrimitiveList.Float.Reader intrinsics){
        // TODO: find better ways to check validity.
        return intrinsics.get(0)!=0 & intrinsics.get(2)!=0 & intrinsics.get(4)!=0 & intrinsics.get(5)!=0 & intrinsics.get(8)!=0;
    }

    public void updateCameraMatrix(PrimitiveList.Float.Reader intrinsics, boolean wide){
        if (!isIntrinsicsValid(intrinsics))
            return;
        for (int i=0; i<3; i++){
            for (int j=0; j<3; j++){
                if (wide)
                    wide_intrinsics.put(i, j, intrinsics.get(i*3 + j));
                else
                    road_intrinsics.put(i, j, intrinsics.get(i*3 + j));
            }
        }
    }

    public static void SetLatestCameraData(Definitions.FrameData.Reader wideData, Definitions.FrameBuffer.Reader wideBuf, long timestamp) {
        frameWideData = frameData = wideData;
        msgFrameWideBuffer = msgFrameBuffer = wideBuf;
        cameraImageTimestamp = timestamp;
        NeedImage = wideData == null || wideBuf == null;
    }

    public void updateCameraState(){
        while (NeedImage) {
            Thread.yield();
        }
        start = System.currentTimeMillis();
        imgBuffer = updateImageBuffer(msgFrameBuffer, imgBuffer);
        wideImgBuffer = updateImageBuffer(msgFrameWideBuffer, wideImgBuffer);
    }

    public void run(){
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        NeedImage = true;

        snpe = params.getBool("UseSNPE");
        if (snpe)
            imgTensorShape = new int[]{1, 128, 256, 12}; // SNPE only supports NHWC input.

        INDArray netInputBuffer, netInputWideBuffer;

        ph.createPublishers(Arrays.asList("modelRaw"));
        sh.createSubscribers(Arrays.asList("pulseDesire", "liveCalibration", "lateralPlan"));

        inputShapeMap.put("input_imgs", imgTensorShape);
        inputShapeMap.put("big_input_imgs", imgTensorShape);
        inputShapeMap.put("features_buffer", featureTensorShape);
        inputShapeMap.put("desire", desireTensorShape);
        inputShapeMap.put("traffic_convention", trafficTensorShape);
        inputShapeMap.put("nav_features", navFeaturesTensorShape);
        inputShapeMap.put("nav_instructions", navInstructionsTensorShape);
        outputShapeMap.put("outputs", outputTensorShape);

        inputMap.put("features_buffer", featuresNDArr);
        inputMap.put("desire", desireNDArr);
        inputMap.put("traffic_convention", trafficNDArr);
        outputMap.put("outputs", netOutputs);

        modelRunner.init(inputShapeMap, outputShapeMap);
        modelRunner.warmup();

        updateCameraState();
        updateCameraMatrix(frameWideData.getIntrinsics(), true);
        updateCameraMatrix(frameData.getIntrinsics(), false);

        INDArray wrapMatrix = Preprocess.getWrapMatrix(augmentRot, road_intrinsics, wide_intrinsics, utils.WideCameraOnly, false);
        INDArray wrapMatrixWide = Preprocess.getWrapMatrix(augmentRot, road_intrinsics, wide_intrinsics, true, true);

        // TODO:Clean this shit.
        ImagePrepare imagePrepare;
        ImagePrepare imageWidePrepare;
        boolean rgb;
        if (getUseGPU()){
            rgb = msgFrameBuffer.getEncoding() == Definitions.FrameBuffer.Encoding.RGB;
            imagePrepare = new ImagePrepareGPU(FULL_FRAME_SIZE[0], FULL_FRAME_SIZE[1], rgb, msgFrameBuffer.getYWidth(), msgFrameBuffer.getYHeight(),
                    msgFrameBuffer.getYPixelStride(), msgFrameBuffer.getUvWidth(), msgFrameBuffer.getUvHeight(), msgFrameBuffer.getUvPixelStride(),
                    msgFrameBuffer.getUOffset(), msgFrameBuffer.getVOffset(), msgFrameBuffer.getStride());
            rgb = msgFrameWideBuffer.getEncoding() == Definitions.FrameBuffer.Encoding.RGB;
            imageWidePrepare = new ImagePrepareGPU(FULL_FRAME_SIZE[0], FULL_FRAME_SIZE[1], rgb, msgFrameWideBuffer.getYWidth(), msgFrameWideBuffer.getYHeight(),
                    msgFrameWideBuffer.getYPixelStride(), msgFrameWideBuffer.getUvWidth(), msgFrameWideBuffer.getUvHeight(), msgFrameWideBuffer.getUvPixelStride(),
                    msgFrameWideBuffer.getUOffset(), msgFrameWideBuffer.getVOffset(), msgFrameWideBuffer.getStride());
        }
        else{
            rgb = msgFrameBuffer.getEncoding() == Definitions.FrameBuffer.Encoding.RGB;
            imagePrepare = new ImagePrepareCPU(FULL_FRAME_SIZE[0], FULL_FRAME_SIZE[1], rgb, msgFrameBuffer.getYWidth(), msgFrameBuffer.getYHeight(),
                    msgFrameBuffer.getYPixelStride(), msgFrameBuffer.getUvWidth(), msgFrameBuffer.getUvHeight(), msgFrameBuffer.getUvPixelStride(),
                    msgFrameBuffer.getUOffset(), msgFrameBuffer.getVOffset(), msgFrameBuffer.getStride());
            rgb = msgFrameWideBuffer.getEncoding() == Definitions.FrameBuffer.Encoding.RGB;
            imageWidePrepare = new ImagePrepareCPU(FULL_FRAME_SIZE[0], FULL_FRAME_SIZE[1], rgb, msgFrameWideBuffer.getYWidth(), msgFrameWideBuffer.getYHeight(),
                    msgFrameWideBuffer.getYPixelStride(), msgFrameWideBuffer.getUvWidth(), msgFrameWideBuffer.getUvHeight(), msgFrameWideBuffer.getUvPixelStride(),
                    msgFrameWideBuffer.getUOffset(), msgFrameWideBuffer.getVOffset(), msgFrameWideBuffer.getStride());
        }

        lastFrameID = frameData.getFrameId();

        initialized = true;
        params.putBool("ModelDReady", true);
        while (!exit) {
            if (stopped){
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;
            }

            if (sh.updated("lateralPlan")){
                desire = sh.recv("lateralPlan").getLateralPlan().getDesire().ordinal();
                for (int i=0; i<CommonModelF3.DESIRE_LEN; i++)
                    desireIn[i] = i == desire ? 1f : 0f;
            }

            //std::memmove(&s->pulse_desire[0], &s->pulse_desire[DESIRE_LEN], sizeof(float) * DESIRE_LEN*HISTORY_BUFFER_LEN);
            for (int h = 0; h < CommonModelF3.HISTORY_BUFFER_LEN; h++) {
                for (int d = 0; d < CommonModelF3.DESIRE_LEN; d++) {
                    desireNDArr.putScalar(0, d, h, desireNDArr.getFloat(0, d, h+1));
                }
            }
            for (int i=1; i<CommonModelF3.DESIRE_LEN; i++){
                if (desireIn[i] - prevDesire[i] > 0.99f)
                    desireNDArr.putScalar(0, i, CommonModelF3.HISTORY_BUFFER_LEN, desireIn[i]);
                else
                    desireNDArr.putScalar(0, i, CommonModelF3.HISTORY_BUFFER_LEN,0.0f);
                prevDesire[i] = desireIn[i];
            }

            if (sh.updated("liveCalibration")) {
                liveCalib = sh.recv("liveCalibration").getLiveCalibration();
                PrimitiveList.Float.Reader rpy = liveCalib.getRpyCalib();
                for (int i = 0; i < 3; i++) {
                    augmentRot.putScalar(i, rpy.get(i));
                }
                wrapMatrix = Preprocess.getWrapMatrix(augmentRot, road_intrinsics, wide_intrinsics, utils.WideCameraOnly, false);
                wrapMatrixWide = Preprocess.getWrapMatrix(augmentRot, road_intrinsics, wide_intrinsics, true, true);
            }

            NeedImage = true;
            updateCameraState();
            netInputBuffer = imagePrepare.prepare(imgBuffer, wrapMatrix);
            netInputWideBuffer = imageWidePrepare.prepare(wideImgBuffer, wrapMatrixWide);

            if (snpe){
                try (MemoryWorkspace ws = Nd4j.getWorkspaceManager().getAndActivateWorkspace(wsConfig, "ModelD")) {
                // NCHW to NHWC
                netInputBuffer = netInputBuffer.permute(0, 2, 3, 1).dup();
                netInputWideBuffer = netInputWideBuffer.permute(0, 2, 3, 1).dup();
                }
            }

            inputMap.put("input_imgs", netInputBuffer);
            inputMap.put("big_input_imgs", netInputWideBuffer);
            modelRunner.run(inputMap, outputMap);

            // featureTensorShape, 1, FEATURE_LEN, HISTORY_LEN
            //    std::memmove(&s->feature_buffer[0], &s->feature_buffer[FEATURE_LEN], sizeof(float) * FEATURE_LEN*(HISTORY_BUFFER_LEN-1));
            for (int h = 0; h < CommonModelF3.HISTORY_BUFFER_LEN-1; h++) {
                for (int f = 0; f < CommonModelF3.FEATURE_LEN; f++) {
                    featuresNDArr.putScalar(0, f, h, featuresNDArr.getFloat(0, f, h+1));
                }
            }
            //    std::memcpy(&s->feature_buffer[FEATURE_LEN*(HISTORY_BUFFER_LEN-1)], &s->output[OUTPUT_SIZE], sizeof(float) * FEATURE_LEN);
            for (int i = 0; i < CommonModelF3.FEATURE_LEN; i++)
                featuresNDArr.putScalar(0, i,CommonModelF3.HISTORY_BUFFER_LEN - 1, netOutputs[CommonModelF3.OUTPUT_SIZE + i]);

            // publish outputs
            serializeAndPublish();
            // compute runtime stats every 10 runs
            frameDrops = (frameData.getFrameId() - lastFrameID) - 1;
            if (iterationNum > 10) {
                AvgIterationTime = timePerIt / iterationNum;
                iterationNum = 0;
                timePerIt = 0;
                totalFrameDrops += frameDrops;
            } else {
                firstFrameID = lastFrameID;
            }

            lastFrameID = frameData.getFrameId();
            timePerIt += end - start;
            iterationNum++;
        }

        // dispose
        wrapMatrix.close();
        wrapMatrixWide.close();

        for (String inputName : inputMap.keySet()) {
            inputMap.get(inputName).close();
        }
        modelRunner.dispose();
        imagePrepare.dispose();
        imageWidePrepare.dispose();
        ph.releaseAll();
    }

    public void init() {
        if (thread == null) {
            thread = new Thread(this, threadName);
            thread.setDaemon(false);
            thread.start();
        }
    }

    public void serializeAndPublish(){
        end = System.currentTimeMillis();
        msgModelRaw.fill(netOutputs, cameraImageTimestamp, lastFrameID, -1, 0f, end - start);
        ph.publishBuffer("modelRaw", msgModelRaw.serialize(true));
    }

    public long getIterationRate() {
        return timePerIt/iterationNum;
    }

    public float getFrameDropPercent() {
        return (float)100* totalFrameDrops /(lastFrameID-firstFrameID);
    }

    public boolean isRunning() {
        return !stopped;
    }

    public boolean isInitialized(){
        return initialized;
    }

    public void dispose(){
        exit = true;
    }

    public void stop() {
        stopped = true;
    }

    public void start(){
        if (thread == null)
            init();
        stopped = false;
    }
}
