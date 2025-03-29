package code;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaFX音频播放器应用程序
 * 支持格式：WAV, MP3, FLAC, OGG
 */
public class AudioPlayer extends Application {

    // 播放器状态枚举
    private enum PlayerState { STOPPED, PLAYING, PAUSED }

    //============ UI组件 ============//
    private Label fileLabel = new Label("当前文件：未选择");    // 文件路径显示
    private Label statusLabel = new Label("状态：就绪");       // 状态显示
    private Button playButton = new Button("播放");           // 播放按钮
    private Button pauseButton = new Button("暂停");          // 暂停按钮
    private Button resumeButton = new Button("继续");         // 继续按钮
    private Button stopButton = new Button("停止");           // 停止按钮

    //============ 音频组件 ============//
    private volatile PlayerState state = PlayerState.STOPPED; // 当前播放状态（volatile保证可见性）
    private volatile SourceDataLine currentLine;              // 音频输出线路
    private volatile long totalPausedTime = 0;                // 累计暂停时间（微秒）
    private volatile long pausePosition = 0;                  // 暂停时的位置（微秒）
    private File currentFile;                                 // 当前播放文件
    private final ExecutorService executor = Executors.newCachedThreadPool(); // 线程池

    @Override
    public void start(Stage primaryStage) {
        setupUI(primaryStage);     // 初始化用户界面
        setupEventHandlers();      // 设置事件处理器
    }

    /**
     * 初始化用户界面
     * @param stage 主舞台
     */
    private void setupUI(Stage stage) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));  // 设置边距

        //====== 顶部面板（文件选择）======//
        HBox topPanel = new HBox(10);     // 水平布局，间距10像素
        Button openButton = new Button("选择文件");
        topPanel.getChildren().addAll(openButton, fileLabel);
        root.setTop(topPanel);

        //====== 中心面板（控制按钮）======//
        HBox controlPanel = new HBox(10);
        controlPanel.getChildren().addAll(playButton, pauseButton, resumeButton, stopButton);
        root.setCenter(controlPanel);

        //====== 底部面板（状态显示）======//
        VBox statusPanel = new VBox(5);   // 垂直布局，间距5像素
        statusPanel.getChildren().add(statusLabel);
        root.setBottom(statusPanel);

        // 创建场景并显示舞台
        Scene scene = new Scene(root, 500, 150);
        stage.setTitle("JavaFX音频播放器");
        stage.setScene(scene);
        stage.show();

        // 初始化文件选择器
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("音频文件", "*.wav", "*.mp3", "*.flac", "*.ogg"));

        // 文件选择事件处理
        openButton.setOnAction(e -> {
            File file = fileChooser.showOpenDialog(stage);
            if (file != null) {
                currentFile = file;
                updateUI(() -> fileLabel.setText("当前文件：" + file.getName()));
                stopPlayback();         // 停止当前播放
                totalPausedTime = 0;    // 重置暂停计时
            }
        });
    }

    /**
     * 设置按钮事件处理器
     */
    private void setupEventHandlers() {
        playButton.setOnAction(e -> startPlayback());    // 播放
        pauseButton.setOnAction(e -> togglePause());     // 暂停
        resumeButton.setOnAction(e -> resumePlayback()); // 继续
        stopButton.setOnAction(e -> stopPlayback());     // 停止
        updateButtonStates(); // 初始化按钮状态
    }

    /**
     * 开始播放音频
     */
    private void startPlayback() {
        if (currentFile == null) return;

        // 提交播放任务到线程池
        executor.submit(() -> {
            try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(currentFile)) {
                // 获取基础音频格式
                AudioFormat baseFormat = audioStream.getFormat();
                // 转换为目标格式（统一为PCM_SIGNED 16bit）
                AudioFormat targetFormat = getTargetFormat(baseFormat);

                try (AudioInputStream convertedStream =
                             AudioSystem.getAudioInputStream(targetFormat, audioStream)) {

                    updateState(PlayerState.PLAYING); // 更新状态为播放中

                    // 初始化音频输出线路
                    DataLine.Info info = new DataLine.Info(
                            SourceDataLine.class,
                            targetFormat);
                    currentLine = (SourceDataLine) AudioSystem.getLine(info);
                    currentLine.open(targetFormat);
                    currentLine.start();

                    // 流式读取和播放音频数据
                    byte[] buffer = new byte[4096]; // 4KB缓冲区
                    int bytesRead;
                    while ((bytesRead = convertedStream.read(buffer)) != -1
                            && state == PlayerState.PLAYING) { // 仅在播放状态下继续
                        currentLine.write(buffer, 0, bytesRead);
                    }
                }
            } catch (Exception ex) {
                handleError(ex); // 处理异常
            } finally {
                safeClose(); // 确保资源释放
            }
        });
    }

    /**
     * 切换暂停状态
     */
    private void togglePause() {
        if (state == PlayerState.PLAYING) {
            updateState(PlayerState.PAUSED); // 更新状态为暂停
            if (currentLine != null) {
                currentLine.stop(); // 停止音频输出
                // 记录暂停位置
                long currentTime = currentLine.getMicrosecondPosition();
                totalPausedTime += currentTime - pausePosition;
                System.out.println("暂停，累计播放时间：" + totalPausedTime + " 微秒");
            }
        }
    }

    /**
     * 恢复播放
     */
    private void resumePlayback() {
        if (state == PlayerState.PAUSED && currentFile != null) {
            executor.submit(() -> {
                try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(currentFile)) {
                    AudioFormat baseFormat = audioStream.getFormat();
                    AudioFormat targetFormat = getTargetFormat(baseFormat);

                    try (AudioInputStream convertedStream =
                                 AudioSystem.getAudioInputStream(targetFormat, audioStream)) {
                        AudioFormat finalFormat = convertedStream.getFormat();

                        // 计算需要跳过的字节数（考虑帧对齐）
                        long bytesToSkip = calculateSkipPosition(targetFormat);
                        bytesToSkip = (bytesToSkip / finalFormat.getFrameSize()) * finalFormat.getFrameSize();

                        // 实际跳过字节操作
                        long totalSkipped = 0;
                        byte[] skipBuffer = new byte[4096];
                        while (totalSkipped < bytesToSkip) {
                            long toRead = Math.min(skipBuffer.length, bytesToSkip - totalSkipped);
                            int read = convertedStream.read(skipBuffer, 0, (int) toRead);
                            if (read == -1) break;
                            totalSkipped += read;
                        }

                        updateState(PlayerState.PLAYING); // 更新状态为播放中

                        // 重新初始化音频线路
                        DataLine.Info info = new DataLine.Info(
                                SourceDataLine.class,
                                targetFormat);
                        currentLine = (SourceDataLine) AudioSystem.getLine(info);
                        currentLine.open(targetFormat);
                        currentLine.start();

                        // 继续播放剩余数据
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = convertedStream.read(buffer)) != -1
                                && state == PlayerState.PLAYING) {
                            currentLine.write(buffer, 0, bytesRead);
                        }
                    }
                } catch (Exception ex) {
                    handleError(ex);
                } finally {
                    safeClose();
                }
            });
        }
    }

    /**
     * 计算需要跳过的字节数
     * @param format 音频格式
     * @return 需要跳过的字节数
     */
    private long calculateSkipPosition(AudioFormat format) {
        return (long)(totalPausedTime * format.getSampleRate() * format.getFrameSize() / 1_000_000.0);
    }

    /**
     * 获取目标音频格式（统一为PCM_SIGNED 16bit）
     * @param baseFormat 原始音频格式
     * @return 转换后的音频格式
     */
    private AudioFormat getTargetFormat(AudioFormat baseFormat) {
        // 如果已经是PCM_SIGNED 16bit则直接使用
        if (baseFormat.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && baseFormat.getSampleSizeInBits() == 16) {
            return baseFormat;
        }
        // 否则转换为标准格式
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(),
                16,
                baseFormat.getChannels(),
                baseFormat.getChannels() * 2, // 帧大小 = 通道数 * 2字节（16bit）
                baseFormat.getSampleRate(),
                false); // 小端字节序
    }

    /**
     * 停止播放
     */
    private void stopPlayback() {
        updateState(PlayerState.STOPPED); // 更新状态为停止
        safeClose();       // 释放资源
        totalPausedTime = 0; // 重置暂停计时
        pausePosition = 0;
    }

    /**
     * 安全关闭音频资源
     */
    private void safeClose() {
        if (currentLine != null) {
            currentLine.stop();
            currentLine.close();
            currentLine = null; // 释放资源
        }
    }

    /**
     * 更新播放器状态
     * @param newState 新状态
     */
    private void updateState(PlayerState newState) {
        state = newState;
        Platform.runLater(this::updateButtonStates); // 在UI线程更新按钮状态
    }

    /**
     * 更新按钮状态
     */
    private void updateButtonStates() {
        // 设置按钮可用状态
        playButton.setDisable(state != PlayerState.STOPPED);
        pauseButton.setDisable(state != PlayerState.PLAYING);
        resumeButton.setDisable(state != PlayerState.PAUSED);
        stopButton.setDisable(state == PlayerState.STOPPED);

        // 更新状态标签
        String status;
        switch (state) {
            case PLAYING: status = "播放中..."; break;
            case PAUSED:  status = "已暂停";   break;
            case STOPPED: status = "已停止";   break;
            default:     status = "未知状态"; break;
        }
        statusLabel.setText("状态：" + status);
    }

    /**
     * 处理播放错误
     * @param cause 异常对象
     */
    private void handleError(Throwable cause) {
        Platform.runLater(() -> {
            statusLabel.setText("状态：播放出错");
            new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR,
                    "播放错误: " + cause.getMessage()
            ).show();
        });
    }

    /**
     * 在UI线程执行更新操作
     * @param update 需要执行的Runnable
     */
    private void updateUI(Runnable update) {
        Platform.runLater(update);
    }

    @Override
    public void stop() {
        executor.shutdownNow(); // 关闭线程池
        safeClose();            // 确保资源释放
    }

    public static void main(String[] args) {
        launch(args); // 启动JavaFX应用
    }
}