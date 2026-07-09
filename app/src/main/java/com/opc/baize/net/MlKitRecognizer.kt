package com.opc.baize.net

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.opc.baize.InkView
import java.util.concurrent.TimeUnit

/**
 * 识别主通道：ML Kit Digital Ink Recognition（zh-Hani），直接吃笔画轨迹——
 * 老板方针：识别是老技术，别用图片大模型。
 *
 * 华为无 GMS 的现实预期：模型下载走 Google 服务，无 GMS 设备上
 * download/recognize 大概率抛异常。任何一步失败 → broken 置位，
 * 调用方（MainActivity）自动降级到 Mac /api/ask 通道。
 * broken 不做永久锁死：每轮识别失败都单独降级，Wi-Fi 切换等临时故障可自愈；
 * 只有初始化本身失败（类加载/模型标识不可用）才永久降级。
 */
class MlKitRecognizer(
    /** 模型下载状态回调：非 null=显示小灰字提示，null=收起（一次性） */
    private val onDownloadStatus: (String?) -> Unit,
) {
    @Volatile
    var brokenForever = false
        private set

    private var recognizer: DigitalInkRecognizer? = null
    private var downloadTask: Task<Void>? = null

    fun init(downloadingTip: String) {
        try {
            val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani")
                ?: throw IllegalStateException("zh-Hani 模型标识不可用")
            val model = DigitalInkRecognitionModel.builder(identifier).build()
            val manager = RemoteModelManager.getInstance()
            manager.isModelDownloaded(model)
                .addOnSuccessListener { downloaded ->
                    if (!downloaded) onDownloadStatus(downloadingTip)
                }
            downloadTask = manager.download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener { onDownloadStatus(null) }
                .addOnFailureListener { onDownloadStatus(null) }
            recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
        } catch (t: Throwable) {
            // GMS 缺失时常见：类初始化/服务绑定直接炸——永久降级
            brokenForever = true
            onDownloadStatus(null)
        }
    }

    /**
     * 阻塞识别（在工作线程调用）。任何异常上抛，由调用方降级。
     * 返回首候选文本（可能为空串 = 识别不出）。
     */
    fun recognize(strokes: List<List<InkView.Pt>>): String {
        if (brokenForever) throw IllegalStateException("ML Kit 已判定不可用")
        val rec = recognizer ?: throw IllegalStateException("ML Kit 未初始化")
        // 先等模型就绪（首次下载中给 20s 宽限，超时本轮降级、下轮再试）
        downloadTask?.let { Tasks.await(it, 20, TimeUnit.SECONDS) }
        val inkBuilder = Ink.builder()
        for (s in strokes) {
            val sb = Ink.Stroke.builder()
            for (p in s) sb.addPoint(Ink.Point.create(p.x, p.y, p.t))
            inkBuilder.addStroke(sb.build())
        }
        val result = Tasks.await(rec.recognize(inkBuilder.build()), 20, TimeUnit.SECONDS)
        return result.candidates.firstOrNull()?.text?.trim() ?: ""
    }
}
