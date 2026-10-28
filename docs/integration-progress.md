# 原改造进度

## 2026-10-28：v0.2.0 ModelProvider 接入

用户已反馈 v0.1.4 的授权、模型列表、文本、图片四项实机验证全部通过。保留 v0.1.4 tag 为验证基线。

将已验证协议/网络/验签实现从 debug source set 移到 main，debug 验证页保留但不再单独显示启动图标。新增 ModelProvider、AnthropicModelProvider、SubscriptionModelProvider；MainActivity 沿用原识别与后台提交，只替换模型调用入口。设置可切换，默认为原 API。订阅携带最近六轮本地历史与原人格提示，不写服务端历史，不自动跨通道计费。

新增应用级 SubscriptionGateway 与原生授权设置页。凭据仍仅内存保存，浏览器授权和模型调用串行执行；返回主界面保留会话，进程结束后需重新登录。未添加持久化令牌，不宣称已完成长期会话与多账号管理。独立 debug 包名避免影响旧版笔迹和历史。

验证：原协议/回调/网络/公钥检查；新增真实请求编解码单测（六轮截断、角色、图片、禁用服务端存储、拒绝空模型及远程图片 URL），CI 执行单测、lint、debug/release 构建。主界面手写应答仍需平板实机验证。

下一步按第一阶段审计：PDF 依赖兼容探针 → 独立 PDF 页面与矢量笔迹持久化 → 选区提问和手动提交 → Room/Drive → Agent。未实现能力不标记完成。

## v0.2.1：本地 PDF 练习首版

AndroidPdfViewer 当前 Maven Central 的 dev.zwander:pdfviewer:5.0.0 AAR 实测 minCompileSdk=36，POM 含 Kotlin2.3.10/core-ktx1.18.0；不对墨灵 compile34 工程强行升级。按审计备选使用 Android 平台 PdfRenderer，通过 PdfEngine 隔离；不重写 PDF 解码器。

现有设置页增加 PDF 工作区入口。SAF 导入≤100MB 本地副本，以原始 PDF SHA256 为文档ID；单页渲染最长边1800像素，页面坐标按平台渲染页尺寸（point）定义。写字、拖动、±缩放、撤销、矩形选区、手动整页/局部批改与选区解释，均在独立原生 Activity 内，不改变自由手写流程。当前是矩形选择，尚无自由套索和压感笔宽表现；压力/时间保留在矢量数据中。

存储通过 PaperStore：original.pdf、每页 ink.json、提交源笔迹快照、结果JSON分开；AtomicFile 保存笔迹/结果。每次落笔结束排队保存，提交前存不可变源快照，结果带 documentId/page/sourceRevision/region/sourceVectors；继续写字不会改变已发出的图片或覆盖当前笔迹。结果从本页批改记录读取。模型请求只手动触发，订阅以外的文本 Provider 明确不接受图片。

仍待设备验证：PDF 渲染/触控/大文件/杀进程恢复；无圆形套索、逐笔橡皮擦、原PDF导出、Room迁移、Drive授权同步、Agent、跨设备冲突处理。截图只是模型输入派生物，不作为唯一保存格式。原文件损坏或笔迹尺寸不匹配时停止加载并保留原文件。

构建历史：v0.2.0首次CI因debug应用label与主清单冲突失败，未发布APK；v0.2.1显式声明label覆盖。

## v0.2.2：工作区生命周期收尾
跨工作区重建共享串行文件执行器，避免旧页面提交结果和新页面恢复笔迹并行访问 AtomicFile；退出页面关闭 PDF 解码器，但已提交请求仍可保存独立结果。刷新令牌失败清除会话后同步更新 Provider 就绪状态。
