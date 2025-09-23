# MQTT Macchiatto 文档系统

这是 MQTT Macchiatto 项目的文档系统，采用 Markdown 格式编写，便于维护和更新。

## 文档结构

```
web/docs/
├── README.md           # 文档说明
├── index.json          # 文档目录配置
├── about.md           # 关于项目
├── installation.md    # 引入依赖
├── quick-start.md     # 快速开始
├── mqttput.md         # MqttPut 消息监听
├── mqttpush.md        # MqttPush 消息发布
├── mqttqos.md         # MQTTQos 质量等级
├── mqttmonitor.md     # MQTTMonitor 监听器
└── mqttreport.md      # MQTTReport 消息上报
```

## 文档特点

### ✅ 优势

1. **独立维护**：每个章节都是独立的 Markdown 文件，便于单独编辑和维护
2. **版本控制友好**：Markdown 格式对 Git 版本控制非常友好
3. **动态加载**：文档页面支持动态加载 Markdown 文件，无需重新构建
4. **语法高亮**：集成 Prism.js 提供专业的代码语法高亮
5. **响应式设计**：支持桌面端和移动端访问
6. **搜索友好**：纯文本格式便于搜索和索引

### 🔧 技术实现

- **Markdown 解析**：使用 marked.js 解析 Markdown 文件
- **语法高亮**：使用 Prism.js 提供代码高亮
- **动态导航**：基于 index.json 配置文件生成导航
- **单页应用**：使用 History API 实现单页应用体验

## 如何添加新文档

### 1. 创建 Markdown 文件

在 `web/docs/` 目录下创建新的 `.md` 文件：

```bash
touch web/docs/new-feature.md
```

### 2. 编写文档内容

使用标准的 Markdown 语法编写文档：

```markdown
# 新功能说明

## 概述

这是一个新功能的说明文档。

## 使用方法

\`\`\`java
// 代码示例
public class Example {
    public void demo() {
        System.out.println("Hello World");
    }
}
\`\`\`

## 注意事项

> 这是一个重要提示
```

### 3. 更新导航配置

编辑 `web/docs/index.json` 文件，添加新文档的配置：

```json
{
  "sections": [
    {
      "title": "新功能",
      "items": [
        {
          "id": "new-feature",
          "title": "新功能说明",
          "description": "新功能的详细说明",
          "file": "new-feature.md"
        }
      ]
    }
  ]
}
```

### 4. 更新导航 HTML

编辑 `web/docs.html` 文件，在侧边栏导航中添加新的链接：

```html
<li class="nav-item">
    <a class="nav-link" data-page="new-feature">新功能说明</a>
</li>
```

## 文档编写规范

### Markdown 语法

1. **标题层级**：使用 `#` 到 `####` 表示不同层级的标题
2. **代码块**：使用三个反引号包围代码，并指定语言类型
3. **表格**：使用标准的 Markdown 表格语法
4. **链接**：使用 `[文本](链接)` 格式
5. **强调**：使用 `**粗体**` 和 `*斜体*`

### 代码示例

```java
// 好的代码示例
@Service
public class ExampleService {
    
    private MqttPush mqttPush = new MqttPush();
    
    public void sendMessage(String topic, String message) {
        mqttPush.push(topic, message, MQTTQos.AT_LEAST_ONCE);
    }
}
```

### 信息提示

使用引用语法创建提示框：

```markdown
> ⚠️ **重要提示**
> 
> 这是一个重要的注意事项

> ✅ **最佳实践**
> 
> 这是推荐的做法

> 💡 **小贴士**
> 
> 这是一个有用的提示
```

## 维护指南

### 定期更新

1. **版本同步**：当项目版本更新时，同步更新文档中的版本号
2. **API 变更**：当 API 发生变更时，及时更新相关文档
3. **示例代码**：确保示例代码能够正常运行
4. **链接检查**：定期检查文档中的外部链接是否有效

### 质量保证

1. **语法检查**：使用 Markdown 语法检查工具
2. **代码测试**：确保文档中的代码示例能够编译和运行
3. **用户反馈**：收集用户反馈，持续改进文档质量

### 备份策略

1. **版本控制**：所有文档都纳入 Git 版本控制
2. **定期备份**：定期备份文档文件
3. **变更记录**：记录重要的文档变更

## 部署说明

### 本地预览

直接在浏览器中打开 `web/docs.html` 即可预览文档。

### 生产部署

1. 将整个 `web` 目录部署到 Web 服务器
2. 确保服务器支持静态文件访问
3. 配置正确的 MIME 类型支持 `.md` 文件

### CDN 优化

可以将以下资源替换为 CDN 链接以提高加载速度：

- Prism.js 语法高亮库
- marked.js Markdown 解析库
- Font Awesome 图标库
- Google Fonts 字体

## 贡献指南

欢迎为文档贡献内容！请遵循以下步骤：

1. Fork 项目仓库
2. 创建新的分支：`git checkout -b docs/new-feature`
3. 编写或修改文档
4. 提交更改：`git commit -m "docs: 添加新功能文档"`
5. 推送分支：`git push origin docs/new-feature`
6. 创建 Pull Request

## 联系方式

如有文档相关问题，请联系：

- 📧 Email: guolvaita@gmail.com
- 💬 WeChat: AfterTheMoonlight
- 🐛 Issues: [GitHub Issues](https://github.com/rururunu/MQTT-Macchiatto/issues)