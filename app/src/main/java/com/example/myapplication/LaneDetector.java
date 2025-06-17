package com.example.myapplication;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;

import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class LaneDetector {
    private Interpreter tflite;
    private final int inputWidth = 320;
    private final int inputHeight = 256;

    public LaneDetector(AssetManager assetManager, String modelPath) throws IOException {
        tflite = new Interpreter(loadModelFile(assetManager, modelPath));
    }

    private MappedByteBuffer loadModelFile(AssetManager assetManager, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = assetManager.openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public DetectionResult detect(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());

        for (int y = 0; y < inputHeight; y++) {
            for (int x = 0; x < inputWidth; x++) {
                int pixel = resized.getPixel(x, y);
                inputBuffer.putFloat(((pixel >> 16) & 0xFF));  // R
                inputBuffer.putFloat(((pixel >> 8) & 0xFF));   // G
                inputBuffer.putFloat((pixel & 0xFF));          // B
            }
        }

        float[][][][] output = new float[1][inputHeight][inputWidth][1];
        tflite.run(inputBuffer, output);

        Bitmap outputBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < inputHeight; y++) {
            for (int x = 0; x < inputWidth; x++) {
                int gray = output[0][y][x][0] > 0.5 ? 255 : 0; // 判斷是否是車道線
                int color = (gray == 255) ? 0xFF00FF00 : 0x00FFFFFF; // 綠色車道線 + 透明背景
                outputBitmap.setPixel(x, y, color);
            }
        }


        // 計算偏移
        float offset = computeLaneOffset(outputBitmap);
        String direction;
        if (Math.abs(offset) < 10) {
            direction = "保持中線";
        } else if (offset > 0) {
            direction = "向右偏離";
        } else {
            direction = "向左偏離";
        }

        // resize 回原始影像大小
        Bitmap scaledOutput = Bitmap.createScaledBitmap(outputBitmap, bitmap.getWidth(), bitmap.getHeight(), true);
        return new DetectionResult(scaledOutput, direction);
    }

    private float computeLaneOffset(Bitmap mask) {
        int width = mask.getWidth();
        int height = mask.getHeight();

        int startY = height * 3 / 4;  // 計算的起始高度
        int endY = height * 4 / 5;    // 計算的終點高度
        int sumX = 0;
        int count = 0;
        int totalWeight = 0;

        for (int y = startY; y < endY; y++) {  // 掃描多行
            int weight = y - startY + 1;  // 讓靠近底部的影像行影響力更大
            totalWeight += weight;

            for (int x = 0; x < width; x++) {
                int pixel = mask.getPixel(x, y);
                int green = (pixel >> 8) & 0xff;  // 讀取綠色通道（確保是車道線）
                if (green > 200) {  // 判斷是否是車道線
                    sumX += x * weight;  // 加權累積 X 坐標
                    count += weight;
                }
            }
        }

        if (count == 0) return 0; // 沒偵測到車道線
        int avgX = sumX / count;
        return avgX - (width / 2);  // 計算偏移量（正值右偏，負值左偏）
    }


    public static class DetectionResult {
        public final Bitmap resultImage;
        public final String direction;

        public DetectionResult(Bitmap image, String dir) {
            resultImage = image;
            direction = dir;
        }
    }
}


