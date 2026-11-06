# WE 开源绘画升级批次

## 组件取舍

| 项目 | 许可证 | 结论 | 原因与实际接入 |
| --- | --- | --- | --- |
| Drawpile / Drawdance | GPL（现有随附声明） | 保留 | 已接原生笔刷与栅格图层；继续共用保存/历史 |
| AndroidSVG 1.4 | Apache-2.0 | 本批接入 | 稳定 Maven 组件；在既有 Canvas 内渲染矢量路径、渐变、内部组、裁剪与文字，保留源内容 |
| AndroidX ExifInterface 1.3.7 | Apache-2.0 | 本批接入 | 手机照片导入前修正 EXIF 方向；与当前 API34 工具链兼容 |
| PhotoEditor | MIT | 已评估，未接入 | 照片编辑、文本、贴纸成熟，但其 View/历史模型与 WE 原生画布不同；直接嵌入会形成第二套编辑状态 |
| CanHub Android-Image-Cropper | Apache-2.0 | 候选，未接入 | 独立图片裁剪适合后续素材处理，不替换统一画布 |
| CiDrawing / DroidArt | Apache-2.0 / MIT | 候选，未接入 | 图形与文字方案可参考；本批用 AndroidSVG 加现有对象模型覆盖所需范围，避免重复引擎 |

参考来源：
- https://github.com/BigBadaboom/androidsvg
- https://bigbadaboom.github.io/androidsvg/download.html
- https://developer.android.com/jetpack/androidx/releases/exifinterface
- https://github.com/burhanrashid52/PhotoEditor
- https://github.com/CanHub/Android-Image-Cropper
- https://github.com/ruochao/cidrawing
- https://github.com/Cleveroad/DroidArt

## 实际模型

新增 svg/text/image 为带四角几何的持久对象；仍储存在同一份笔迹文档中。SVG 内部结构保留为源文本，由成熟解析器渲染；整体可变换、复制、删除，源内容可替换。SVG 内部分组、裁剪不能称作 WE 原生图层组或跨层蒙版。

PNG/JPEG 图片由 App 导入或 MCP 传入 base64；App 导入最多读取20MB，先采样、修正方向，再压缩到240KB以内。MCP 图片最多350KB base64，解码每边不超过2048。SVG最多128KB且须有viewBox，不加载外部资源；文本最多8000字。文档对象源内容合计最多200万字符。大对象可用 get_canvas_context 的 object_index/content_offset/content_limit 分页读取，避免中继响应超限。

旧文档保持兼容。含新对象的文档需要0.9设备端；降级0.8会拒绝读取未知操作，而非丢弃源内容。

## 联合部署

本批先通过PR上的 JVM、原生及 Android 设备回归，再部署APK和相同提交的中继。服务端继续使用 tools/deploy-we-drawing-relay.sh；脚本只重启 WE 服务并保留配对与原有来源配置。health 的 tools_schema_sha256 必须匹配设备包附带的 schema 文件。脚本核对该哈希，避免“仍是16个工具”被误判为升级成功。

暂不改动线上服务；本会话缺少服务器终端，不宣称已完成部署。
