# MCP 生成可直接落笔的内容

核心输出是带内容的纸面：AI 生成内容和版面，用户直接在文字、题目、参考图上作答或圈画。原内容与笔迹独立保存，坐标一致。

`publish_learning_page` 接收 `page`（推荐）或旧的 `content`，两者只能提供一个。以下是 page 示例，外层还需 workspace_id、request 和 operation_id：

```json
{
  "title": "一般过去时",
  "mode": "handwriting",
  "orientation": "portrait",
  "handedness": "right",
  "font_size": 20,
  "line_height": 32,
  "blocks": [
    {"id": "intro", "type": "text", "text": "先观察时间提示，再填写动词形式。"},
    {"id": "q1", "type": "question", "text": "He ____ (go) to school yesterday.", "answer_lines": 3},
    {"id": "draw", "type": "drawing", "text": "画出你的理解", "height": 200, "grid": "dots"}
  ]
}
```

block 支持 heading、text、question、writing、drawing、figure。question 自动附带有 parent_id 的答题区，默认保留三行笔写空间；题目与答题区在能够容纳时一起换页。长文本按实际行分页，不直接裁掉超出部分。height 单位为 PDF 页面点，范围 64～500，过高的独立区域适配可用页面高度。

portrait 为 600×840，landscape 为 840×600。默认内容字号 20、行高 32；handedness 改变批注栏所在侧，外侧及底部保留落笔空间。屏幕缩放、PDF 渲染和笔迹继续使用原有页面坐标变换。不是声称已经对所有平板尺寸和手掌姿势做过物理设备验证。

figure 使用 0～1000 的区域内相对坐标：shapes 支持 line（x/y/x2/y2）、rect/ellipse（x/y/width/height）、text（x/y/text），颜色为 #RRGGBB。适合参考线、几何框架及简单示意图，不执行 HTML、SVG 脚本或远程图片。

输出包含 PDF、原始 source_path 和 layout_path。read_page 返回 layout.regions，每块包含 id、type、page、x/y/width/height、text、parent_id。AI 将笔迹坐标与这些区域对应，结合返回的 PDF+笔迹图像理解反馈，再发布新页。图像不能替代不清晰字迹的澄清，区域归属也不能直接当成答题正确性的判断。

生成的内容与笔迹可以重叠；答题区只是排版建议，不限制用户落笔位置。笔迹支持颜色、粗细、整笔擦除与撤销；擦除不删除原生成内容。旧笔迹缺少 color 时仍按黑色读取。

绘画模式不默认触发停笔识别；手写模式仍可选择自动继续。打字仍使用系统输入法，包含其自带语音能力。Drawpile 是绘画交互参考，本版没有把它作为已集成引擎。
