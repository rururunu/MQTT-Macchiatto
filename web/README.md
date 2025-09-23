# MQTT Macchiatto 官网

这是为 MQTT Macchiatto 项目创建的官方网站，展示了该Spring Boot MQTT封装工具的特性和使用方法。

## 特色功能

### 🎨 设计特点
- **官方Logo**：使用项目官方SVG logo，完美展现MQTT Macchiatto品牌形象
- **红色主色调**：采用现代化的红色渐变设计，体现项目的活力和专业性
- **响应式布局**：完美适配桌面端和移动端设备
- **流畅动画**：丰富的交互动画和过渡效果，包括logo发光效果
- **代码高亮**：使用Prism.js提供专业的语法高亮

### 📱 页面结构
- **首页 Hero 区域**：项目介绍和核心代码展示
- **特性介绍**：详细说明项目优势和对比
- **快速开始**：三步快速集成指南
- **代码示例**：分类展示消息监听、发布和高级用法
- **联系信息**：开发者联系方式和项目链接

### 🚀 交互功能
- 平滑滚动导航
- 标签页切换
- 代码一键复制
- 返回顶部按钮
- 滚动进度条
- 响应式导航栏

## 文件结构

```
web/
├── index.html                # 主页面
├── styles.css                # 样式文件
├── script.js                 # 交互脚本
├── MQTT-Macchiatto-Logo.svg  # 官方logo文件
├── favicon.svg               # 网站图标
├── test-prism.html           # Prism.js测试页面
└── README.md                 # 说明文档
```

## 使用方法

1. 直接在浏览器中打开 `index.html` 文件
2. 或者使用本地服务器：
   ```bash
   # 使用 Python
   python -m http.server 8000
   
   # 使用 Node.js
   npx serve .
   
   # 使用 PHP
   php -S localhost:8000
   ```

## 技术栈

- **HTML5**：语义化标签和现代化结构
- **CSS3**：Flexbox/Grid布局、动画、渐变
- **JavaScript ES6+**：模块化代码、现代API
- **Font Awesome**：图标库
- **响应式设计**：移动端优先

## 浏览器支持

- Chrome 60+
- Firefox 55+
- Safari 12+
- Edge 79+

## 自定义配置

### 修改主色调
在 `styles.css` 中搜索 `#dc2626` 和 `#b91c1c`，替换为你想要的颜色值。

### 添加新的代码示例
在 `index.html` 的 `examples` 部分添加新的标签页和内容。

### 修改联系信息
更新 `contact` 部分的联系方式和链接。

## 部署建议

### GitHub Pages
1. 将 `web` 目录内容推送到 `gh-pages` 分支
2. 在仓库设置中启用 GitHub Pages

### Netlify
1. 连接 GitHub 仓库
2. 设置构建目录为 `web`
3. 自动部署

### Vercel
1. 导入项目
2. 设置根目录为 `web`
3. 一键部署

## 性能优化

- 使用 CDN 加载外部资源
- 图片懒加载
- CSS/JS 压缩
- 启用 Gzip 压缩
- 添加缓存策略

## 贡献

欢迎提交 Issue 和 Pull Request 来改进这个官网！

## 许可证

与主项目保持一致，使用 Apache License 2.0